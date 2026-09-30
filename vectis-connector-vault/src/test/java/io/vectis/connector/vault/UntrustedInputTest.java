// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.BacklogSourceException;
import io.vectis.extension.spi.SourceItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Hostile or odd files built on the fly: things a fixture in git cannot carry portably. */
class UntrustedInputTest {

    @TempDir
    Path temp;

    private Path vault;
    private Path space;

    @BeforeEach
    void createSpace() throws IOException {
        vault = Files.createDirectory(temp.resolve("vault"));
        space = Files.createDirectory(vault.resolve("demo"));
        Files.writeString(space.resolve("_backlog-meta.yaml"), "idPolicy:\n  prefix: DEMO\n");
        for (String tier : List.of("raw", "wiki", "output")) {
            Files.createDirectory(space.resolve(tier));
        }
    }

    @Test
    void aSymbolicLinkToAFileIsNotFollowed() throws IOException {
        Path outside = Files.writeString(temp.resolve("outside.md"), item("DEMO-1"));
        assumeSymlinks(space.resolve("raw/DEMO-1.md"), outside);

        BacklogSnapshot snapshot = read();

        assertEquals(List.of(), snapshot.items());
        assertOneProblem(snapshot, "raw/DEMO-1.md", "a symbolic link, not followed; skipped");
    }

    @Test
    void aSymbolicLinkToADirectoryIsNotFollowed() throws IOException {
        Path outside = Files.createDirectory(temp.resolve("elsewhere"));
        Files.writeString(outside.resolve("DEMO-1.md"), item("DEMO-1"));
        Files.delete(space.resolve("output"));
        assumeSymlinks(space.resolve("output"), outside);

        BacklogSnapshot snapshot = read();

        assertEquals(List.of(), snapshot.items());
        assertTrue(snapshot.problems().contains("vault/demo/output/: a symbolic link, not followed; skipped"),
                snapshot.problems()::toString);
    }

    @Test
    void anOversizedFileIsSkipped() throws IOException {
        String padding = "x".repeat(VaultFiles.MAX_FILE_BYTES);
        Files.writeString(space.resolve("raw/DEMO-1.md"), item("DEMO-1") + padding);
        Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2"));

        BacklogSnapshot snapshot = read();

        assertEquals(List.of("DEMO-2"), Fixtures.keys(snapshot));
        assertOneProblem(snapshot, "raw/DEMO-1.md", "larger than 256 KiB; skipped");
    }

    @Test
    void aFileThatIsNotUtf8IsSkipped() throws IOException {
        byte[] latin1 = item("DEMO-1").replace("A title", "Café").getBytes(StandardCharsets.ISO_8859_1);
        Files.write(space.resolve("raw/DEMO-1.md"), latin1);

        BacklogSnapshot snapshot = read();

        assertEquals(List.of(), snapshot.items());
        assertOneProblem(snapshot, "raw/DEMO-1.md", "not valid UTF-8; skipped");
    }

    @Test
    void windowsLineEndingsAndAByteOrderMarkAreAccepted() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-1.md"), "﻿" + item("DEMO-1").replace("\n", "\r\n"));

        BacklogSnapshot snapshot = read();

        assertEquals("A title", Fixtures.item(snapshot, "DEMO-1").title());
        assertEquals("Body.", Fixtures.item(snapshot, "DEMO-1").body());
        assertEquals(List.of(), snapshot.problems());
    }

    @Test
    void anUnreadableMetaFileFallsBackToTheSpaceName() throws IOException {
        Files.writeString(space.resolve("_backlog-meta.yaml"), "idPolicy: [unclosed\n");
        Files.writeString(space.resolve("raw/DEMO-1.md"), item("DEMO-1"));

        BacklogSnapshot snapshot = read();

        assertEquals("DEMO", snapshot.keyPrefix());
        assertEquals(List.of("DEMO-1"), Fixtures.keys(snapshot));
        assertOneProblem(snapshot, "_backlog-meta.yaml", "key prefix taken from the space name as DEMO");
    }

    @Test
    void aMetaFileWithoutAPrefixFallsBackToTheSpaceName() throws IOException {
        Files.writeString(space.resolve("_backlog-meta.yaml"), "idPolicy:\n  tracker: local\n");

        BacklogSnapshot snapshot = read();

        assertEquals("DEMO", snapshot.keyPrefix());
        assertOneProblem(snapshot, "_backlog-meta.yaml", "no 'idPolicy.prefix'; key prefix taken from the space name as DEMO");
    }

    @Test
    void aKeyPrefixThatCannotBeAWorkspaceKeyFailsTheReadAsAWhole() throws IOException {
        Files.writeString(space.resolve("_backlog-meta.yaml"), "idPolicy:\n  prefix: 'demo.*'\n");

        assertThrows(BacklogSourceException.class, this::read);
    }

    @ParameterizedTest
    @ValueSource(strings = {"x: !!binary \"a\"", "x: !!int \"abc\""})
    void aValueTheParserCannotBuildIsAProblemNotAnEscapedException(String line) throws IOException {
        Files.writeString(space.resolve("_backlog-meta.yaml"), "idPolicy:\n  prefix: DEMO\n" + line + "\n");
        Files.writeString(space.resolve("raw/DEMO-1.md"), item("DEMO-1").replace("status:", line + "\nstatus:"));
        Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2"));

        BacklogSnapshot snapshot = read();

        assertEquals("DEMO", snapshot.keyPrefix());
        assertEquals(List.of("DEMO-2"), Fixtures.keys(snapshot));
        assertOneProblem(snapshot, "_backlog-meta.yaml", "key prefix taken from the space name as DEMO");
        assertOneProblem(snapshot, "raw/DEMO-1.md", "; skipped");
        assertTrue(Fixtures.problemsAbout(snapshot, "raw/DEMO-1.md").get(0)
                .contains("front-matter has a value the YAML parser cannot build ("), snapshot.problems()::toString);
    }

    @Test
    void aNullOrBlankListEntryIsDroppedAndTheItemKept() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-1.md"), item("DEMO-1").replace("status:",
                "labels: [a, ~]\ndependencies:\n- DEMO-2\n-\nrelates: [DEMO-2, ~]\nstatus:"));

        BacklogSnapshot snapshot = read();

        SourceItem item = Fixtures.item(snapshot, "DEMO-1");
        assertEquals(List.of("a"), item.labels());
        assertEquals(Map.of(SourceItem.DEPENDS_ON, List.of("DEMO-2"), SourceItem.RELATES_TO, List.of("DEMO-2")),
                item.links());
        assertEquals(List.of(
                "vault/demo/raw/DEMO-1.md: 'dependencies' has 1 entry that is not text; dropped",
                "vault/demo/raw/DEMO-1.md: 'relates' has 1 entry that is not text; dropped",
                "vault/demo/raw/DEMO-1.md: 'labels' has 1 entry that is not text; dropped"), snapshot.problems());
    }

    @Test
    void aTimestampLabelIsKeptAsWrittenNotShiftedToUtc() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-1.md"), item("DEMO-1").replace("status:",
                "labels: [2026-01-15T23:30:00-05:00]\nstatus:"));

        BacklogSnapshot snapshot = read();

        assertEquals(List.of("2026-01-15T23:30:00-05:00"), Fixtures.item(snapshot, "DEMO-1").labels());
        assertEquals(List.of(), snapshot.problems());
    }

    @Test
    void aSprintDateWithATimeOfDayIsNotTakenAsADay() throws IOException {
        Files.createDirectory(space.resolve("sprints"));
        Files.writeString(space.resolve("sprints/DEMO-S1.md"), "---\nkind: sprint\nsprintId: DEMO-S1\nstate: active\n"
                + "start: 2026-01-15T23:30:00-05:00\nend: 2026-01-29\n---\n");

        BacklogSnapshot snapshot = read();

        assertNull(snapshot.sprints().get(0).start());
        assertEquals(LocalDate.of(2026, 1, 29), snapshot.sprints().get(0).end());
        assertOneProblem(snapshot, "sprints/DEMO-S1.md", "'start' is not a date (YYYY-MM-DD); left empty");
    }

    private BacklogSnapshot read() {
        return new VaultBacklogSource(vault, "demo").read();
    }

    private static String item(String key) {
        return "---\nkind: story\nid: " + key + "\ntitle: A title\nstatus: TO DO\n---\n\nBody.\n";
    }

    private static void assumeSymlinks(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not supported here: " + e);
        }
    }

    private static void assertOneProblem(BacklogSnapshot snapshot, String path, String expected) {
        List<String> problems = Fixtures.problemsAbout(snapshot, path);
        assertEquals(1, problems.size(), snapshot.problems()::toString);
        assertTrue(problems.get(0).endsWith(expected), problems::toString);
    }
}
