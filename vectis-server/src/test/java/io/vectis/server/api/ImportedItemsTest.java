// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.connector.vault.VaultBacklogSource;
import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.persistence.ImportedItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * VEC-54 AC1 and AC5 without a database: the connector reads a small vault with an item in every
 * status, and each lands in the column its status maps to, with the fields the web client reads.
 */
class ImportedItemsTest {

    @TempDir
    Path vault;

    private void item(String tier, int number, String status, String extra) throws IOException {
        Path dir = Files.createDirectories(vault.resolve("vec").resolve(tier));
        Files.writeString(dir.resolve("VEC-" + number + ".md"), """
                ---
                kind: story
                id: VEC-%d
                title: Item %d
                status: %s
                %s---
                Body of item %d.
                """.formatted(number, number, status, extra, number));
    }

    private Map<String, ImportedItem> imported() throws IOException {
        Files.writeString(Files.createDirectories(vault.resolve("vec")).resolve("_backlog-meta.yaml"),
                "idPolicy:\n  prefix: VEC\n");
        for (String tier : List.of("raw", "wiki", "output")) {
            Files.createDirectories(vault.resolve("vec").resolve(tier));
        }
        BacklogSnapshot snapshot = new VaultBacklogSource(vault, "vec").read();
        assertEquals(List.of(), snapshot.problems());
        var out = new LinkedHashMap<String, ImportedItem>();
        snapshot.items().forEach(i -> out.put(i.key(), ImportedItems.of(i)));
        return out;
    }

    @Test
    void everyStatusLandsInItsColumn() throws IOException {
        item("raw", 1, "TO DO", "");
        item("raw", 2, "REFINED", "");
        item("raw", 3, "PLANNED", "");
        item("wiki", 4, "IN PROGRESS", "");
        item("wiki", 5, "IN REVIEW", "");
        item("output", 6, "DONE", "");
        item("raw", 7, "NO GO", "");
        item("wiki", 8, "NO GO", "");

        Map<String, ImportedItem> items = imported();

        Map<String, String> columns = new LinkedHashMap<>();
        items.forEach((key, item) -> columns.put(key, item.columnKey()));
        assertEquals(Map.of("VEC-1", "backlog", "VEC-2", "backlog", "VEC-3", "todo", "VEC-4", "in-progress",
                "VEC-5", "in-review", "VEC-6", "done", "VEC-7", "no-go", "VEC-8", "no-go"), columns);
    }

    @Test
    void aRefinedItemIsInBacklogWithARefinedLabelAndNoOtherItemHasOne() throws IOException {
        item("raw", 20, "REFINED", "labels:\n- edition-community\n");
        item("raw", 21, "REFINED", "labels:\n- refined\n");
        item("raw", 22, "TO DO", "");
        item("raw", 23, "PLANNED", "");

        Map<String, ImportedItem> items = imported();

        assertEquals(List.of("edition-community", "refined"), items.get("VEC-20").fields().get("labels"));
        assertEquals(List.of("refined"), items.get("VEC-21").fields().get("labels"), "once");
        assertFalse(items.get("VEC-22").fields().containsKey("labels"));
        assertFalse(items.get("VEC-23").fields().containsKey("labels"));
        assertEquals("backlog", items.get("VEC-20").columnKey());
    }

    @Test
    void theFieldsTheWebClientReadsAreCarriedAndAnEmptyOneIsLeftOut() throws IOException {
        item("raw", 10, "TO DO", """
                epic: VEC-2
                storyPoints: 8
                priority: P0
                labels:
                - edition-community
                dependencies:
                - VEC-9
                relates:
                - VEC-42
                """);
        item("raw", 11, "TO DO", "");

        Map<String, ImportedItem> items = imported();

        Map<String, Object> full = items.get("VEC-10").fields();
        assertEquals("story", full.get("type"));
        assertEquals("TO DO", full.get("status"));
        assertEquals("VEC-2", full.get("parentId"));
        assertEquals(8L, full.get("storyPoints"));
        assertEquals("P0", full.get("priority"));
        assertEquals(List.of("edition-community"), full.get("labels"));
        assertEquals(List.of("VEC-9"), full.get("dependencies"));
        assertEquals(List.of("VEC-42"), full.get("relates"));
        assertTrue(((String) full.get("description")).contains("Body of item 10."));
        Map<String, Object> bare = items.get("VEC-11").fields();
        for (String absent : List.of("parentId", "storyPoints", "priority", "labels", "dependencies", "relates")) {
            assertFalse(bare.containsKey(absent), absent);
        }
    }

    @Test
    void aRankFollowsTheNumberInTheKeySoNineSortsBeforeTen() {
        assertEquals("00000009", ImportedItems.rank("VEC-9"));
        assertEquals("00000010", ImportedItems.rank("VEC-10"));
        assertTrue(ImportedItems.rank("VEC-9").compareTo(ImportedItems.rank("VEC-10")) < 0);
    }
}
