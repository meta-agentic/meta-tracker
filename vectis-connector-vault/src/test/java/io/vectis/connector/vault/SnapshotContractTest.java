// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.SourceSprint;
import io.vectis.extension.spi.StatusCategory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the SPI value types. They live here because the SPI module carries no
 * dependencies at all, not even a test framework.
 */
class SnapshotContractTest {

    @Test
    void itemRequiresItsIdentityAndDefaultsItsCollections() {
        var item = new SourceItem("DEMO-1", "Title", "story", "TO DO", StatusCategory.NOT_STARTED,
                null, null, null, null, null, null, null, "vault/demo/raw/DEMO-1.md");

        assertEquals(List.of(), item.labels());
        assertEquals(Map.of(), item.links());
        assertEquals("", item.body());
        assertThrows(NullPointerException.class, () -> new SourceItem(null, "Title", "story", "TO DO",
                StatusCategory.NOT_STARTED, null, null, null, null, null, null, null, "origin"));
        assertThrows(NullPointerException.class, () -> new SourceItem("DEMO-1", "Title", "story", "TO DO",
                null, null, null, null, null, null, null, null, "origin"));
        assertThrows(IllegalArgumentException.class, () -> new SourceItem("DEMO-1", " ", "story", "TO DO",
                StatusCategory.NOT_STARTED, null, null, null, null, null, null, null, "origin"));
    }

    @Test
    void itemCopiesItsCollections() {
        var labels = new ArrayList<>(List.of("a"));
        var targets = new ArrayList<>(List.of("DEMO-2"));
        var links = new HashMap<String, List<String>>(Map.of(SourceItem.DEPENDS_ON, targets));
        var item = new SourceItem("DEMO-1", "Title", "story", "TO DO", StatusCategory.NOT_STARTED,
                null, null, null, null, labels, links, "", "origin");

        labels.add("b");
        targets.add("DEMO-3");
        links.put(SourceItem.RELATES_TO, List.of("DEMO-4"));

        assertEquals(List.of("a"), item.labels());
        assertEquals(Map.of(SourceItem.DEPENDS_ON, List.of("DEMO-2")), item.links());
        assertThrows(UnsupportedOperationException.class, () -> item.labels().add("c"));
    }

    @Test
    void snapshotCopiesItsListsAndRequiresAPrefix() {
        var problems = new ArrayList<String>();
        var snapshot = new BacklogSnapshot("DEMO", null, null, problems);
        problems.add("later");

        assertEquals(List.of(), snapshot.items());
        assertEquals(List.of(), snapshot.sprints());
        assertEquals(List.of(), snapshot.problems());
        assertThrows(NullPointerException.class, () -> new BacklogSnapshot(null, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new BacklogSnapshot("", List.of(), List.of(), List.of()));
    }

    @Test
    void itemKeepsTheSourcesLinkOrder() {
        var links = new LinkedHashMap<String, List<String>>();
        links.put(SourceItem.RELATES_TO, List.of("DEMO-4"));
        links.put(SourceItem.DEPENDS_ON, List.of("DEMO-2"));
        var item = new SourceItem("DEMO-1", "Title", "story", "TO DO", StatusCategory.NOT_STARTED,
                null, null, null, null, null, links, "", "origin");

        assertEquals(List.of(SourceItem.RELATES_TO, SourceItem.DEPENDS_ON), List.copyOf(item.links().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> item.links().put("x", List.of()));
    }

    @Test
    void aSnapshotIsIncompleteOnlyWithAFatalProblem() {
        assertTrue(new BacklogSnapshot("DEMO", List.of(), List.of(), List.of("vault/demo/raw/DEMO-1.md: skipped"))
                .complete());
        assertFalse(new BacklogSnapshot("DEMO", List.of(), List.of(), List.of(BacklogSnapshot.FATAL + "stopped"))
                .complete());
    }

    @Test
    void sprintRequiresKeyAndState() {
        assertThrows(NullPointerException.class, () -> new SourceSprint("DEMO-S1", null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SourceSprint(" ", SourceSprint.State.FUTURE, null, null, null));
    }
}
