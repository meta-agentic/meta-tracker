// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Files written to break the reader rather than to describe work: recursion, log
 * forging, oversized echoes, and floods. Each must cost a bounded report, or stop the
 * read with an explicit incomplete marker.
 */
class HostileInputTest {

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
        Files.writeString(space.resolve("raw/DEMO-1.md"), item("DEMO-1", "status: TO DO\n"));
    }

    @Test
    void aSelfReferencingValueIsOneSkippedFileNotACrash() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2", "status: TO DO\nstoryPoints: &a [[*a]]\n"));

        assertOnlyTheGoodItemAndOneSkip(read(), "raw/DEMO-2.md");
    }

    @Test
    void aSelfReferencingKeyIsOneSkippedFileNotACrash() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2", "status: TO DO\n? {q: &b [[*b]]}\n: x\n"));

        assertOnlyTheGoodItemAndOneSkip(read(), "raw/DEMO-2.md");
    }

    @Test
    void aKeyThatIsNotTextIsRefusedRatherThanMerged() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2", "status: TO DO\n1: a\n\"1\": b\n"));

        BacklogSnapshot snapshot = assertOnlyTheGoodItemAndOneSkip(read(), "raw/DEMO-2.md");
        assertTrue(snapshot.problems().get(0).contains("has a key that is not text"), snapshot.problems()::toString);
    }

    @Test
    void controlCharactersInAnEchoedValueCannotForgeALine() throws IOException {
        Files.writeString(space.resolve("raw/DEMO-2.md"),
                item("DEMO-2", "status: \"BAD\\nFAKE: forged line\\e[31m\\x85\\x7F\"\n"));

        List<String> problems = Fixtures.problemsAbout(read(), "raw/DEMO-2.md");

        assertEquals(1, problems.size(), problems::toString);
        String problem = problems.get(0);
        assertTrue(problem.contains("'BAD\\nFAKE: forged line\\u001B[31m\\u0085\\u007F'"), problem);
        assertTrue(problem.chars().noneMatch(c -> c < 0x20 || (c >= 0x7F && c <= 0x9F)), problem);
    }

    @Test
    void aLongEchoedValueIsCut() throws IOException {
        String longStatus = "X".repeat(200);
        String hugeNumber = "9".repeat(20_000);
        Files.writeString(space.resolve("raw/DEMO-2.md"),
                item("DEMO-2", "status: " + longStatus + "\nstoryPoints: " + hugeNumber + "\n"));

        List<String> problems = Fixtures.problemsAbout(read(), "raw/DEMO-2.md");

        assertEquals(2, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'" + "X".repeat(Text.MAX_ECHO) + "…'"), problems.get(0));
        problems.forEach(problem -> assertTrue(problem.length() < 250, problem));
    }

    @Test
    void tooManyEntriesStopTheReadAndMarkItIncomplete() throws IOException {
        for (int n = 2; n <= 6; n++) {
            Files.writeString(space.resolve("raw/DEMO-" + n + ".md"), item("DEMO-" + n, "status: TO DO\n"));
        }

        BacklogSnapshot snapshot = new VaultBacklogSource(vault, "demo", 4, Long.MAX_VALUE, Integer.MAX_VALUE).read();

        assertFalse(snapshot.complete());
        assertEquals(List.of(), snapshot.items(), "the tier that overflowed the listing is not read");
        String last = snapshot.problems().get(snapshot.problems().size() - 1);
        assertEquals("FATAL: vault/demo: stopped reading after more than 4 directory entries; "
                + "the snapshot is incomplete", last);
    }

    @Test
    void tooManyBytesStopTheReadAndMarkItIncomplete() throws IOException {
        Files.writeString(space.resolve("wiki/DEMO-2.md"), item("DEMO-2", "status: IN PROGRESS\n"));
        Files.writeString(space.resolve("output/DEMO-3.md"), item("DEMO-3", "status: DONE\n"));
        long firstTwoFiles = Files.size(space.resolve("_backlog-meta.yaml")) + Files.size(space.resolve("raw/DEMO-1.md"));

        BacklogSnapshot snapshot = new VaultBacklogSource(vault, "demo", 1000, firstTwoFiles + 1, Integer.MAX_VALUE).read();

        assertFalse(snapshot.complete());
        assertFalse(Fixtures.keys(snapshot).contains("DEMO-3"), "reading stopped before output/");
        assertTrue(snapshot.problems().stream().anyMatch(p -> p.startsWith("FATAL: ")), snapshot.problems()::toString);
    }

    @Test
    void aFloodOfNonTextListEntriesCostsABoundedReportPerFile() throws IOException {
        String flood = "[" + String.join(",", Collections.nCopies(5_000, "{}")) + "]";
        int files = 50;
        for (int n = 2; n < 2 + files; n++) {
            Files.writeString(space.resolve("raw/DEMO-" + n + ".md"), item("DEMO-" + n,
                    "status: TO DO\nlabels: " + flood + "\ndependencies: " + flood + "\nrelates: " + flood + "\n"));
        }

        BacklogSnapshot snapshot = read();

        assertEquals(1 + files, snapshot.items().size(), "every flooded item is kept");
        assertTrue(snapshot.items().stream().allMatch(item -> item.labels().isEmpty() && item.links().isEmpty()));
        assertEquals(files * 6, snapshot.problems().size(), "a cut and a count for each of three fields");
        assertTrue(snapshot.problems().stream().mapToInt(String::length).sum() < files * 6 * 120,
                "the report does not grow with the lists");
        assertEquals(List.of(
                "vault/demo/raw/DEMO-2.md: 'dependencies' has 5000 entries; only the first 256 are kept",
                "vault/demo/raw/DEMO-2.md: 'dependencies' has 256 entries that are not text; dropped",
                "vault/demo/raw/DEMO-2.md: 'relates' has 5000 entries; only the first 256 are kept",
                "vault/demo/raw/DEMO-2.md: 'relates' has 256 entries that are not text; dropped",
                "vault/demo/raw/DEMO-2.md: 'labels' has 5000 entries; only the first 256 are kept",
                "vault/demo/raw/DEMO-2.md: 'labels' has 256 entries that are not text; dropped"),
                Fixtures.problemsAbout(snapshot, "raw/DEMO-2.md"));
        assertTrue(snapshot.complete());
    }

    @Test
    void aLongListOfTextIsCutToTheCap() throws IOException {
        String ones = "[" + String.join(",", Collections.nCopies(50_000, "1")) + "]";
        Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2", "status: TO DO\nlabels: " + ones + "\n"));

        BacklogSnapshot snapshot = read();

        assertEquals(EntryMapper.MAX_LIST_ENTRIES, Fixtures.item(snapshot, "DEMO-2").labels().size());
        assertEquals(List.of("vault/demo/raw/DEMO-2.md: 'labels' has 50000 entries; only the first 256 are kept"),
                snapshot.problems());
    }

    @Test
    void tooManyProblemsStopTheReadAndMarkItIncomplete() throws IOException {
        for (int n = 2; n <= 10; n++) {
            Files.writeString(space.resolve("raw/DEMO-" + n + ".md"), item("DEMO-" + n, "status: BLOCKED\n"));
        }

        BacklogSnapshot snapshot = new VaultBacklogSource(vault, "demo", 1000, Long.MAX_VALUE, 5).read();

        assertFalse(snapshot.complete());
        assertEquals(7, snapshot.problems().size(), snapshot.problems()::toString);
        assertEquals("FATAL: vault/demo: stopped reading after more than 5 problems; the snapshot is incomplete",
                snapshot.problems().get(6));
    }

    @Test
    void oneFileReportsAtMostSixteenProblems() throws IOException {
        Path file = Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2", "status: TO DO\n"));
        var problems = new ArrayList<String>();

        Optional<String> result = VaultBacklogSource.parse(file, "o", budget(), problems, (entry, report) -> {
            IntStream.range(0, 100).forEach(n -> report.accept("o: problem " + n));
            return Optional.of("mapped");
        });

        assertEquals(Optional.of("mapped"), result);
        assertEquals(VaultBacklogSource.MAX_PROBLEMS_PER_FILE, problems.size(), problems::toString);
        assertEquals("o: problem 14", problems.get(14));
        assertEquals("o: 85 more problems not listed", problems.get(15));
    }

    @Test
    void aFaultInTheReaderIsNamedAndSkipsOnlyThatFile() throws IOException {
        Path file = Files.writeString(space.resolve("raw/DEMO-2.md"), item("DEMO-2", "status: TO DO\n"));
        var problems = new ArrayList<String>();

        Optional<String> result = VaultBacklogSource.parse(file, "o", budget(), problems, (entry, report) -> {
            report.accept("o: reported before the fault");
            throw new IllegalStateException("a bug");
        });

        assertEquals(Optional.empty(), result);
        assertEquals(List.of("o: the reader failed on this file (IllegalStateException); skipped"), problems);
    }

    @Test
    void aVaultWithinBudgetIsComplete() {
        assertTrue(read().complete());
    }

    private BacklogSnapshot read() {
        return new VaultBacklogSource(vault, "demo").read();
    }

    private static ReadBudget budget() {
        return new ReadBudget(Integer.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static String item(String key, String extra) {
        return "---\nkind: story\nid: " + key + "\ntitle: A title\n" + extra + "---\n\nBody.\n";
    }

    private static BacklogSnapshot assertOnlyTheGoodItemAndOneSkip(BacklogSnapshot snapshot, String path) {
        assertEquals(List.of("DEMO-1"), Fixtures.keys(snapshot));
        assertEquals(1, snapshot.problems().size(), snapshot.problems()::toString);
        assertEquals(1, Fixtures.problemsAbout(snapshot, path).size(), snapshot.problems()::toString);
        assertTrue(snapshot.problems().get(0).endsWith("; skipped"), snapshot.problems()::toString);
        assertTrue(snapshot.complete());
        return snapshot;
    }
}
