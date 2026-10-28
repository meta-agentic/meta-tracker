// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * VEC-54 against PostgreSQL 16: {@code POST /api/v1/imports/vault} over a copy of this repository's
 * own {@code .vault/vec}. What should be on the board is worked out here from the files
 * themselves, by status, not from the code under test.
 */
@QuarkusTest
@TestProfile(ImportTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class VaultImportApiTest {

    private static final Path REPOSITORY_VAULT = Path.of("..", ".vault").toAbsolutePath().normalize();
    private static final Path VAULT = ImportTestProfile.VAULT;
    private static final Pattern STATUS = Pattern.compile("(?m)^status:\\s*(.+?)\\s*$");

    /** The column each vault status belongs in on the Scrum board with Refined after Backlog. */
    private static final Map<String, String> COLUMN = Map.of(
            "TO DO", "backlog", "REFINED", "refined", "PLANNED", "todo", "IN PROGRESS", "in-progress",
            "IN REVIEW", "in-review", "DONE", "done", "NO GO", "no-go");

    @BeforeEach
    void freshVaultAndNoWorkspace() throws IOException {
        Files.createDirectories(VAULT);
        deleteTree(VAULT);
        copyTree(REPOSITORY_VAULT, VAULT);
        update("delete from workspace where key = 'VEC'");
    }

    // --- AC1: every item once, in the column its status maps to ----------------------------------

    @Test
    void importProvisionsTheWorkspaceFromScrumWithRefinedAndShowsEveryItemOnceInItsColumn() throws IOException {
        Map<String, String> expected = expectedColumns();
        assertTrue(expected.size() >= 10, "the repository's backlog is the fixture: " + expected.size());

        post("vault").then().statusCode(200)
                .body("workspace", equalTo("VEC"))
                .body("provisioned", equalTo(true))
                .body("created", equalTo(expected.size()))
                .body("updated", equalTo(0))
                .body("unchanged", equalTo(0));

        given().get("/api/v1/workspaces/VEC").then().statusCode(200)
                .body("template.key", equalTo("scrum")).body("template.version", equalTo(1));
        given().get("/api/v1/workspaces/VEC/boards").then().statusCode(200)
                .body("[0].columns.key", equalTo(
                        List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go")));
        Map<String, String> shown = shownColumns();
        assertEquals(expected, shown, "every item once, in its column");
        assertTrue(shown.containsValue("refined") && shown.containsValue("done") && shown.containsValue("backlog"));
    }

    // --- AC2: idempotent, and an edit updates its item and nothing else --------------------------

    @Test
    void runningTheImportAgainChangesNothing() throws IOException {
        int n = expectedColumns().size();
        post("vault").then().statusCode(200);
        List<Map<String, Object>> before = items();

        post("vault").then().statusCode(200)
                .body("provisioned", equalTo(false))
                .body("created", equalTo(0)).body("updated", equalTo(0)).body("unchanged", equalTo(n));

        assertEquals(before, items(), "every item, version and rank as it was");
    }

    @Test
    void anEditedItemFileUpdatesThatItemAndNothingElse() throws IOException {
        post("vault").then().statusCode(200);
        List<Map<String, Object>> before = items();
        Path file = anItemFile();
        String key = file.getFileName().toString().replace(".md", "");
        Files.writeString(file, Files.readString(file).replaceFirst("(?m)^title: .*$", "title: Renamed by an edit"));

        post("vault").then().statusCode(200)
                .body("created", equalTo(0)).body("updated", equalTo(1)).body("unchanged", equalTo(before.size() - 1));

        List<Map<String, Object>> after = items();
        assertEquals(before.size(), after.size(), "no item is duplicated");
        Map<String, Object> edited = byKey(after).get(key);
        assertEquals("Renamed by an edit", edited.get("title"));
        assertEquals(2, ((Number) edited.get("version")).intValue());
        assertEquals(byKey(before).get(key).get("rank"), edited.get("rank"));
        Map<String, Map<String, Object>> others = byKey(after);
        others.remove(key);
        Map<String, Map<String, Object>> was = byKey(before);
        was.remove(key);
        assertEquals(was, others, "every other item untouched");
    }

    @Test
    void anItemWhoseStatusChangedMovesToTheColumnOfItsNewStatus() throws IOException {
        post("vault").then().statusCode(200);
        Path file = anItemFile();
        String key = file.getFileName().toString().replace(".md", "");
        String was = STATUS_OF.apply(Files.readString(file));
        String now = was.equals("PLANNED") ? "TO DO" : "PLANNED";
        assertEquals(COLUMN.get(was), shownColumns().get(key));
        Files.writeString(file, Files.readString(file).replaceFirst("(?m)^status: .*$", "status: " + now));

        post("vault").then().statusCode(200).body("updated", equalTo(1));

        assertEquals(COLUMN.get(now), shownColumns().get(key));
    }

    @Test
    void twoFirstImportsAtOnceLeaveOneWorkspaceWithEveryItemOnce() throws IOException {
        int n = expectedColumns().size();

        var a = CompletableFuture.supplyAsync(() -> post("vault"));
        var b = CompletableFuture.supplyAsync(() -> post("vault"));
        Response ra = a.join();
        Response rb = b.join();

        assertEquals(200, ra.statusCode(), ra.asString());
        assertEquals(200, rb.statusCode(), rb.asString());
        assertEquals(n, ra.jsonPath().getInt("created") + rb.jsonPath().getInt("created"));
        assertEquals(n, items().size());
    }

    // --- read-only -------------------------------------------------------------------------------

    @Test
    void theVaultIsNotWrittenTo() throws IOException {
        Map<String, String> before = digests(VAULT);

        post("vault").then().statusCode(200);
        post("vault").then().statusCode(200);

        assertEquals(before, digests(VAULT));
    }

    // --- errors, as problem+json ------------------------------------------------------------------

    @Test
    void anUnknownSourceIsA404() {
        post("jira").then().statusCode(404).contentType(startsWith("application/problem+json"))
                .body("detail", equalTo("import source jira does not exist"));
    }

    @Test
    void aVaultThatCannotBeReadIsA422AndWritesNothing() throws IOException {
        deleteTree(VAULT.resolve("vec"));

        post("vault").then().statusCode(422).contentType(startsWith("application/problem+json"))
                .body("detail", containsString("vec"));

        given().get("/api/v1/workspaces/VEC").then().statusCode(404);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static final java.util.function.Function<String, String> STATUS_OF = text -> {
        Matcher status = STATUS.matcher(text);
        assertTrue(status.find());
        return status.group(1);
    };

    /** Some item of the vault that is not started, whichever the backlog holds today. */
    private static Path anItemFile() throws IOException {
        try (Stream<Path> files = Files.list(VAULT.resolve("vec/raw"))) {
            return files.filter(f -> f.getFileName().toString().matches("VEC-\\d+\\.md")).sorted().findFirst()
                    .orElseThrow();
        }
    }

    private static Response post(String source) {
        return given().post("/api/v1/imports/" + source);
    }

    /** Key to column, from the vault files: the directory is not consulted, the status line is. */
    private static Map<String, String> expectedColumns() throws IOException {
        var out = new TreeMap<String, String>();
        for (String tier : List.of("raw", "wiki", "output")) {
            try (Stream<Path> files = Files.list(VAULT.resolve("vec").resolve(tier))) {
                for (Path file : files.filter(f -> f.getFileName().toString().matches("VEC-\\d+\\.md")).toList()) {
                    Matcher status = STATUS.matcher(Files.readString(file));
                    assertTrue(status.find(), file.toString());
                    out.put(file.getFileName().toString().replace(".md", ""), COLUMN.get(status.group(1)));
                }
            }
        }
        return out;
    }

    private static Map<String, String> shownColumns() {
        var out = new TreeMap<String, String>();
        items().forEach(item -> out.put((String) item.get("key"), (String) item.get("column")));
        return out;
    }

    private static List<Map<String, Object>> items() {
        JsonPath page = given().get("/api/v1/workspaces/VEC/items?limit=200").jsonPath();
        assertNull(page.get("next"), "the backlog fits one page");
        return page.getList("items");
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> items) {
        var out = new TreeMap<String, Map<String, Object>>();
        items.forEach(item -> out.put((String) item.get("key"), item));
        return out;
    }

    private static Map<String, String> digests(Path root) throws IOException {
        var out = new TreeMap<String, String>();
        try (Stream<Path> all = Files.walk(root)) {
            for (Path file : all.filter(Files::isRegularFile).toList()) {
                try {
                    out.put(root.relativize(file).toString(), HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return out;
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path source : all.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            for (Path path : all.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(VAULT)).toList()) {
                Files.delete(path);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void update(String statement) {
        Config config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
             PreparedStatement st = c.prepareStatement(statement)) {
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
