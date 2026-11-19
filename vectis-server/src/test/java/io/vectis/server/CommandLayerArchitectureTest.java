// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.smallrye.mutiny.Uni;
import io.vectis.command.EditItem;
import io.vectis.command.ItemCommands;
import io.vectis.domain.Item;
import io.vectis.persistence.ItemRepository;
import io.vectis.persistence.WorkspaceEventLog;
import io.vectis.server.api.ItemsResource;
import io.vertx.mutiny.sqlclient.Pool;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every state change the server makes for a client goes through the command layer (ADR-VEC-04 D8):
 * no class of the server calls a write of the persistence layer or reaches the database itself.
 * The check reads the compiled classes' constant pools, so a direct call and a method reference
 * are both caught, and a new resource that writes on its own fails the build here.
 *
 * <p>A write is any method of a repository or of {@link WorkspaceEventLog} whose name is not a
 * read's ({@link #READ}): a new write is caught whatever it is called, and a new read is named as
 * the reads are. The import is the one exemption ({@link #RECONCILE}).
 */
class CommandLayerArchitectureTest {

    /** The names the persistence layer gives its reads. */
    static final Pattern READ = Pattern.compile("(find|list|page|snapshot|summaries)\\p{Alnum}*");

    /**
     * The vault import does not carry a client's command: it reconciles a bound workspace with its
     * source, the system of record (ADR-VEC-04 D1, D6), and is the only writer there.
     */
    static final Set<String> RECONCILE = Set.of("io/vectis/server/api/VaultImport");

    private static final List<String> SQL = List.of("io/vertx/sqlclient/", "io/vertx/mutiny/sqlclient/",
            "io/vertx/pgclient/", "io/vertx/mutiny/pgclient/", "java/sql/", "javax/sql/");

    @Test
    void noServerClassChangesStateOutsideTheCommandLayer() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(serverClasses())) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                found.addAll(violations(Files.readAllBytes(file), RECONCILE));
            }
        }
        assertEquals(List.of(), found);
    }

    @Test
    void theImportIsExemptBecauseItWritesAndForNoOtherReason() {
        assertFalse(violations(bytes("io.vectis.server.api.VaultImport"), Set.of()).isEmpty(),
                "the import no longer writes on its own: drop its exemption");
    }

    // --- the check itself, one case each ------------------------------------------------------

    @Test
    void aDirectCallToAnItemWriteIsReported() {
        assertEquals(List.of(reported(EditsAnItem.class, "io.vectis.persistence.ItemRepository.edit")),
                violations(bytes(EditsAnItem.class.getName()), Set.of()));
    }

    @Test
    void aReferenceToAnItemWriteIsReported() {
        assertEquals(List.of(reported(DeletesByReference.class, "io.vectis.persistence.ItemRepository.delete")),
                violations(bytes(DeletesByReference.class.getName()), Set.of()));
    }

    @Test
    void aDirectCallToTheEventLogIsReported() {
        assertEquals(List.of(reported(WritesTheLog.class, "io.vectis.persistence.WorkspaceEventLog.writeToWorkspace")),
                violations(bytes(WritesTheLog.class.getName()), Set.of()));
    }

    @Test
    void aDirectUseOfTheDatabaseClientIsReported() {
        List<String> found = violations(bytes(QueriesTheDatabase.class.getName()), Set.of());
        assertFalse(found.isEmpty());
        assertEquals(List.of(), found.stream().filter(v -> !v.contains("io.vertx.mutiny.sqlclient.")).toList());
    }

    @Test
    void aRepositoryReadIsNotReported() {
        assertEquals(List.of(), violations(bytes(ReadsAnItem.class.getName()), Set.of()));
    }

    @Test
    void aWriteThroughTheCommandLayerIsNotReported() {
        assertEquals(List.of(), violations(bytes(EditsThroughTheCommands.class.getName()), Set.of()));
    }

    @Test
    void aNestedClassOfAnExemptClassIsExempt() {
        assertEquals(List.of(), violations(bytes(EditsAnItem.Nested.class.getName()),
                Set.of(EditsAnItem.class.getName().replace('.', '/'))));
    }

    /** Each method of a repository or the event log this class references that is not a read. */
    static List<String> violations(byte[] classFile, Set<String> exempt) {
        ClassModel model = ClassFile.of().parse(classFile);
        String self = model.thisClass().asInternalName();
        if (exempt.stream().anyMatch(e -> self.equals(e) || self.startsWith(e + "$"))) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof MemberRefEntry ref && writer(ref.owner().asInternalName())
                    && !READ.matcher(ref.name().stringValue()).matches()) {
                out.add(dotted(self) + " references " + dotted(ref.owner().asInternalName()) + "."
                        + ref.name().stringValue());
            } else if (entry instanceof ClassEntry type && SQL.stream().anyMatch(type.asInternalName()::startsWith)) {
                out.add(dotted(self) + " references " + dotted(type.asInternalName()));
            }
        }
        return out;
    }

    private static boolean writer(String owner) {
        return owner.startsWith("io/vectis/persistence/")
                && (owner.endsWith("Repository") || owner.equals("io/vectis/persistence/WorkspaceEventLog"));
    }

    private static String dotted(String internal) {
        return internal.replace('/', '.');
    }

    private static String reported(Class<?> type, String method) {
        return type.getName() + " references " + method;
    }

    private static Path serverClasses() {
        try {
            return Path.of(ItemsResource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String className) {
        try (InputStream in = CommandLayerArchitectureTest.class.getResourceAsStream(
                "/" + className.replace('.', '/') + ".class")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- fixtures: compiled, never run ---------------------------------------------------------

    static final class EditsAnItem {
        Uni<Item> run(ItemRepository items, Item item) {
            return items.edit(item, 1, null);
        }

        static final class Nested {
            Uni<Item> run(ItemRepository items, Item item) {
                return items.edit(item, 1, null);
            }
        }
    }

    static final class DeletesByReference {
        BiFunction<Item, String, Uni<Void>> run(ItemRepository items) {
            return items::delete;
        }
    }

    static final class WritesTheLog {
        Uni<Object> run(WorkspaceEventLog log) {
            return log.writeToWorkspace(UUID.randomUUID(), null,
                    scope -> Uni.createFrom().<WorkspaceEventLog.Change<Object>>nullItem());
        }
    }

    static final class QueriesTheDatabase {
        Object run(Pool pool) {
            return pool.query("select 1");
        }
    }

    static final class ReadsAnItem {
        Object run(ItemRepository items) {
            return items.findPlaced(UUID.randomUUID(), "X-1");
        }
    }

    static final class EditsThroughTheCommands {
        Object run(ItemCommands commands) {
            return commands.edit(new EditItem("X", "X-1", 1L, "t", null, null));
        }
    }
}
