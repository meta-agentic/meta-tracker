// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.SourceSprint;
import io.vectis.extension.spi.StatusCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The {@code malformed} fixture: every broken file is reported, none stops the read. */
class MalformedVaultTest {

    private static BacklogSnapshot snapshot;

    @BeforeAll
    static void readFixture() {
        snapshot = Fixtures.read("malformed");
    }

    @Test
    void onlyTheReadableItemsSurvive() {
        assertEquals(List.of("DEMO-29", "DEMO-31", "DEMO-34", "DEMO-35"), Fixtures.keys(snapshot));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            raw/DEMO-20.md | no front-matter
            raw/DEMO-21.md | unterminated front-matter
            raw/DEMO-22.md | front-matter is not valid YAML
            raw/DEMO-23.md | front-matter is not valid YAML: found duplicate key title
            raw/DEMO-24.md | front-matter is not a mapping
            raw/DEMO-25.md | file name does not match id 'DEMO-26'
            raw/OTHER-1.md | id 'OTHER-1' is not of the form DEMO-<number>
            raw/DEMO-27.md | 'title' is missing or not text
            raw/DEMO-28.md | front-matter is not valid YAML: Number of aliases for non-scalar nodes exceeds
            raw/DEMO-30.md | front-matter is not valid YAML
            raw/DEMO-32.md | 'kind' is missing or not text
            raw/DEMO-33.md | front-matter is empty
            sprints/DEMO-S9.md | file name does not match sprintId 'DEMO-S8'
            sprints/DEMO-S6.md | not 'kind: sprint'
            """)
    void aBrokenFileIsSkippedAndReported(String path, String reason) {
        List<String> problems = Fixtures.problemsAbout(snapshot, path);

        assertEquals(1, problems.size(), () -> path + " -> " + snapshot.problems());
        assertTrue(problems.get(0).contains(reason), problems::toString);
        assertTrue(problems.get(0).endsWith("; skipped"), problems::toString);
    }

    @Test
    void aBadOptionalFieldDegradesTheItemInsteadOfDroppingIt() {
        SourceItem item = Fixtures.item(snapshot, "DEMO-29");

        assertNull(item.storyPoints());
        assertNull(item.epicKey());
        assertEquals(List.of(), item.labels());
        assertEquals(Map.of(SourceItem.DEPENDS_ON, List.of("DEMO-31")), item.links());
        List<String> problems = Fixtures.problemsAbout(snapshot, "raw/DEMO-29.md");
        assertEquals(4, problems.size(), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("storyPoints 'lots'")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("'epic' is not text")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("'labels' has 1 entry that is not text; dropped")), problems::toString);
        assertTrue(problems.stream().anyMatch(p -> p.contains("'dependencies' has 1 entry that is not text; dropped")), problems::toString);
    }

    @Test
    void negativePointsAreLeftUnestimated() {
        assertNull(Fixtures.item(snapshot, "DEMO-35").storyPoints());
        assertEquals(1, Fixtures.problemsAbout(snapshot, "wiki/DEMO-35.md").size());
    }

    @Test
    void aDuplicateKeyAcrossTiersKeepsTheFurtherTier() {
        SourceItem item = Fixtures.item(snapshot, "DEMO-34");

        assertEquals("DONE", item.status());
        assertEquals(StatusCategory.END_STATE, item.category());
        assertEquals(Outcome.DELIVERED, item.outcome());
        assertEquals("vault/demo/output/DEMO-34.md", item.origin());
        List<String> problems = Fixtures.problemsAbout(snapshot, "output/DEMO-34.md");
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("also at vault/demo/raw/DEMO-34.md"), problems::toString);
    }

    @Test
    void aFileThatIsNotMarkdownIsIgnored() {
        assertTrue(snapshot.problems().stream().noneMatch(p -> p.contains("notes.txt")), snapshot.problems()::toString);
    }

    @Test
    void aBadSprintDateIsLeftEmpty() {
        assertEquals(List.of(new SourceSprint("DEMO-S5", SourceSprint.State.ACTIVE, null, null, null)),
                snapshot.sprints());
        List<String> problems = Fixtures.problemsAbout(snapshot, "sprints/DEMO-S5.md");
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("'start' is not a date"), problems::toString);
    }

    @Test
    void everyProblemNamesTheFileItIsAbout() {
        assertEquals(snapshot.problems().size(), snapshot.problems().stream()
                .filter(p -> p.startsWith("vault/demo/")).count(), snapshot.problems()::toString);
    }
}
