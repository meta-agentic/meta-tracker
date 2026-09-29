// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.StatusCategory;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every row of the status table, against the {@code mapping} fixture: one item per row. */
class StatusMappingTest {

    private static BacklogSnapshot snapshot;

    @BeforeAll
    static void readFixture() {
        snapshot = Fixtures.read("mapping");
    }

    @ParameterizedTest(name = "{1} in {2}/ -> {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            DEMO-1  | TO DO       | raw    | NOT_STARTED
            DEMO-2  | REFINED     | raw    | REFINED
            DEMO-3  | PLANNED     | raw    | NOT_STARTED
            DEMO-4  | NO GO       | raw    | DISCONTINUED
            DEMO-8  | IN PROGRESS | wiki   | IN_PROGRESS
            DEMO-9  | IN REVIEW   | wiki   | IN_PROGRESS
            DEMO-11 | DONE        | output | DONE
            DEMO-13 | done        | output | DONE
            """)
    void knownStatusInItsOwnTierMapsWithoutAProblem(String key, String status, String tier, StatusCategory expected) {
        SourceItem item = Fixtures.item(snapshot, key);

        assertEquals(status, item.status(), "status is kept verbatim");
        assertEquals(expected, item.category());
        assertEquals(List.of(), Fixtures.problemsAbout(snapshot, tier + "/" + key + ".md"));
    }

    @ParameterizedTest(name = "{1} in {2}/ -> {3}, reported")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            DEMO-5  | BLOCKED | raw    | NOT_STARTED | unknown status 'BLOCKED'
            DEMO-6  | DONE    | raw    | NOT_STARTED | status 'DONE' belongs in output/
            DEMO-7  | ""      | raw    | NOT_STARTED | 'status' is missing
            DEMO-10 | REFINED | wiki   | IN_PROGRESS | status 'REFINED' belongs in raw/
            DEMO-12 | TO DO   | output | DONE        | status 'TO DO' belongs in raw/
            """)
    void unknownMissingOrContradictoryStatusIsPlacedByDirectoryAndReported(
            String key, String status, String tier, StatusCategory expected, String reason) {
        SourceItem item = Fixtures.item(snapshot, key);

        assertEquals(status, item.status(), "status is kept verbatim, even when it is wrong");
        assertEquals(expected, item.category(), "the directory wins");
        List<String> problems = Fixtures.problemsAbout(snapshot, tier + "/" + key + ".md");
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains(reason), problems::toString);
        assertTrue(problems.get(0).contains("placed by its directory " + tier + "/ as " + expected),
                problems::toString);
    }

    @Test
    void everyKnownStatusHasAFixtureRow() {
        List<String> covered = snapshot.items().stream().map(SourceItem::status).toList();

        Arrays.stream(VaultStatus.values()).forEach(status -> assertTrue(
                covered.contains(status.label()), status.label() + " has no fixture item"));
    }

    @Test
    void onlyRefinedAndNoGoLeaveTheirTiersCategory() {
        for (VaultStatus status : VaultStatus.values()) {
            StatusCategory expected = switch (status) {
                case REFINED -> StatusCategory.REFINED;
                case NO_GO -> StatusCategory.DISCONTINUED;
                default -> status.tier().category();
            };
            assertEquals(expected, status.category(), status.label());
        }
    }

    @Test
    void noGoIsAnEndStateOfItsOwnNeitherNotStartedNorDone() {
        SourceItem item = Fixtures.item(snapshot, "DEMO-4");

        assertEquals(StatusCategory.DISCONTINUED, item.category());
        assertNotEquals(StatusCategory.NOT_STARTED, item.category());
        assertNotEquals(StatusCategory.DONE, item.category());
    }
}
