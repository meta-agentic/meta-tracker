// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.Outcome;
import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.StatusCategory;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every row of the status table, against the {@code mapping} fixture: one item per row.
 * An empty outcome cell means none, as for every category but an end state.
 */
class StatusMappingTest {

    private static BacklogSnapshot snapshot;

    @BeforeAll
    static void readFixture() {
        snapshot = Fixtures.read("mapping");
    }

    @ParameterizedTest(name = "{1} in {2}/ -> {3} {4}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            DEMO-1  | TO DO       | raw    | START_STATE |
            DEMO-2  | REFINED     | raw    | START_STATE |
            DEMO-3  | PLANNED     | raw    | START_STATE |
            DEMO-4  | NO GO       | raw    | END_STATE   | DISCONTINUED
            DEMO-8  | IN PROGRESS | wiki   | IN_PROGRESS |
            DEMO-9  | IN REVIEW   | wiki   | IN_PROGRESS |
            DEMO-11 | DONE        | output | END_STATE   | DELIVERED
            DEMO-13 | done        | output | END_STATE   | DELIVERED
            """)
    void knownStatusInItsOwnTierMapsWithoutAProblem(String key, String status, String tier,
                                                    StatusCategory category, Outcome outcome) {
        SourceItem item = Fixtures.item(snapshot, key);

        assertEquals(status, item.status(), "status is kept verbatim");
        assertEquals(category, item.category());
        assertEquals(outcome, item.outcome());
        assertEquals(List.of(), Fixtures.problemsAbout(snapshot, tier + "/" + key + ".md"));
    }

    @ParameterizedTest(name = "{1} in {2}/ -> {3} {4}, reported")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
            DEMO-5  | BLOCKED | raw    | START_STATE |           | unknown status 'BLOCKED'        | START_STATE
            DEMO-6  | DONE    | raw    | START_STATE |           | status 'DONE' belongs in output/ | START_STATE
            DEMO-7  | ""      | raw    | START_STATE |           | 'status' is missing             | START_STATE
            DEMO-10 | REFINED | wiki   | IN_PROGRESS |           | status 'REFINED' belongs in raw/ | IN_PROGRESS
            DEMO-12 | TO DO   | output | END_STATE   | DELIVERED | status 'TO DO' belongs in raw/   | END_STATE/DELIVERED
            DEMO-14 | NO GO   | output | END_STATE   | DELIVERED | status 'NO GO' belongs in raw/   | END_STATE/DELIVERED
            """)
    void unknownMissingOrContradictoryStatusIsPlacedByDirectoryAndReported(
            String key, String status, String tier, StatusCategory category, Outcome outcome, String reason,
            String placement) {
        SourceItem item = Fixtures.item(snapshot, key);

        assertEquals(status, item.status(), "status is kept verbatim, even when it is wrong");
        assertEquals(category, item.category(), "the directory wins");
        assertEquals(outcome, item.outcome(), "the directory wins for the outcome too");
        List<String> problems = Fixtures.problemsAbout(snapshot, tier + "/" + key + ".md");
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains(reason), problems::toString);
        assertTrue(problems.get(0).endsWith("placed by its directory " + tier + "/ as " + placement),
                problems::toString);
    }

    @Test
    void everyKnownStatusHasAFixtureRow() {
        List<String> covered = snapshot.items().stream().map(SourceItem::status).toList();

        Arrays.stream(VaultStatus.values()).forEach(status -> assertTrue(
                covered.contains(status.label()), status.label() + " has no fixture item"));
    }

    @Test
    void onlyNoGoLeavesItsTiersCategory() {
        for (VaultStatus status : VaultStatus.values()) {
            StatusCategory expected = status == VaultStatus.NO_GO ? StatusCategory.END_STATE : status.tier().category();
            assertEquals(expected, status.category(), status.label());
        }
    }

    @Test
    void everyStatusHasAnOutcomeExactlyWhenItIsAnEndState() {
        for (VaultStatus status : VaultStatus.values()) {
            assertEquals(status.category() == StatusCategory.END_STATE, status.outcome() != null, status.label());
        }
        for (Tier tier : Tier.values()) {
            assertEquals(tier.category() == StatusCategory.END_STATE, tier.outcome() != null, tier.directory());
        }
    }

    @Test
    void theOutcomeIsNullForEveryItemThatHasNotEnded() {
        snapshot.items().stream().filter(item -> item.category() != StatusCategory.END_STATE)
                .forEach(item -> assertNull(item.outcome(), item.key()));
    }

    @Test
    void noGoEndsDiscontinuedAndDoneEndsDelivered() {
        SourceItem noGo = Fixtures.item(snapshot, "DEMO-4");
        SourceItem done = Fixtures.item(snapshot, "DEMO-11");

        assertEquals(StatusCategory.END_STATE, noGo.category());
        assertEquals(Outcome.DISCONTINUED, noGo.outcome(), "a NO GO item ended without delivering anything");
        assertEquals(StatusCategory.END_STATE, done.category());
        assertEquals(Outcome.DELIVERED, done.outcome());
    }

    @Test
    void refinedIsAStartStateAndKeepsItsVerbatimStatus() {
        SourceItem item = Fixtures.item(snapshot, "DEMO-2");

        assertEquals(StatusCategory.START_STATE, item.category());
        assertNull(item.outcome());
        assertEquals("REFINED", item.status(), "the verbatim status is what sets refined work apart");
    }
}
