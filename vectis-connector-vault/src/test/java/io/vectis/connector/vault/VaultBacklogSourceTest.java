// SPDX-License-Identifier: Apache-2.0
package io.vectis.connector.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.extension.spi.BacklogSnapshot;
import io.vectis.extension.spi.BacklogSourceException;
import io.vectis.extension.spi.SourceItem;
import io.vectis.extension.spi.SourceSprint;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VaultBacklogSourceTest {

    @Test
    void identifiesItselfAsTheVaultSource() {
        var source = new VaultBacklogSource(Fixtures.vault("mapping"), Fixtures.SPACE);

        assertEquals("vault", source.id());
        assertTrue(source.available());
    }

    @Test
    void readsEveryFieldOfAnItem() {
        SourceItem item = Fixtures.item(Fixtures.read("mapping"), "DEMO-1");

        assertEquals("Import: the first item, with a colon", item.title(), "a quoted YAML title");
        assertEquals("story", item.kind());
        assertEquals("TO DO", item.status());
        assertEquals("DEMO-2", item.epicKey());
        assertEquals("DEMO-S2", item.sprintKey());
        assertEquals(5.0, item.storyPoints());
        assertEquals("P1", item.priority());
        assertEquals(List.of("Sprint-1", "edition-community", "2026-01-15"), item.labels(), "an unquoted date label stays as written");
        assertEquals(Map.of(SourceItem.DEPENDS_ON, List.of("DEMO-3"), SourceItem.RELATES_TO, List.of("DEMO-404")),
                item.links(), "a dangling reference is kept, not resolved");
        assertEquals("## Why\n\nA synthetic fixture item.", item.body());
        assertEquals("vault/demo/raw/DEMO-1.md", item.origin());
    }

    @Test
    void optionalFieldsAreEmptyWhenAbsentAndASingleLinkNeedsNoList() {
        BacklogSnapshot snapshot = Fixtures.read("mapping");
        SourceItem epic = Fixtures.item(snapshot, "DEMO-2");
        SourceItem spike = Fixtures.item(snapshot, "DEMO-3");

        assertEquals("epic", epic.kind());
        assertNull(epic.epicKey());
        assertNull(epic.sprintKey());
        assertNull(epic.storyPoints());
        assertEquals(List.of(), epic.labels());
        assertEquals(Map.of(), epic.links());
        assertEquals(3.0, spike.storyPoints(), "an integer estimate");
        assertEquals(Map.of(SourceItem.DEPENDS_ON, List.of("DEMO-1")), spike.links());
    }

    @Test
    void readsTheKeyPrefixFromTheMetaFile() {
        BacklogSnapshot snapshot = Fixtures.read("mapping");

        assertEquals("DEMO", snapshot.keyPrefix());
        assertTrue(snapshot.problems().stream().noneMatch(p -> p.contains("_backlog-meta.yaml")),
                snapshot.problems()::toString);
    }

    @Test
    void generatedIndexFilesAndSubdirectoriesAreNotItems() {
        BacklogSnapshot snapshot = Fixtures.read("mapping");

        assertFalse(Fixtures.keys(snapshot).contains("DEMO-99"), "_index.md is generated output, not source");
        assertTrue(snapshot.problems().stream().noneMatch(p -> p.contains("_index.md") || p.contains("adr")),
                snapshot.problems()::toString);
        assertEquals(15, snapshot.items().size(), Fixtures.keys(snapshot)::toString);
    }

    @Test
    void itemsAreOrderedByKeyNumber() {
        assertEquals(
                List.of("DEMO-1", "DEMO-2", "DEMO-3", "DEMO-4", "DEMO-5", "DEMO-6", "DEMO-7",
                        "DEMO-8", "DEMO-9", "DEMO-10", "DEMO-11", "DEMO-12", "DEMO-13", "DEMO-14", "DEMO-15"),
                Fixtures.keys(Fixtures.read("mapping")));
    }

    @Test
    void readsSprintsWithQuotedAndUnquotedDates() {
        List<SourceSprint> sprints = Fixtures.read("mapping").sprints();

        assertEquals(List.of(
                new SourceSprint("DEMO-S1", SourceSprint.State.CLOSED,
                        LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 1, 20)),
                new SourceSprint("DEMO-S2", SourceSprint.State.ACTIVE,
                        LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 3), null),
                new SourceSprint("DEMO-S3", SourceSprint.State.FUTURE, null, null, null)), sprints);
    }

    @Test
    void anUnknownSprintStateIsTakenAsFutureAndReported() {
        List<String> problems = Fixtures.problemsAbout(Fixtures.read("mapping"), "sprints/DEMO-S3.md");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("unknown sprint state 'paused'"), problems::toString);
    }

    @Test
    void theSameVaultReadsToAnEqualSnapshot() {
        assertEquals(Fixtures.read("mapping"), Fixtures.read("mapping"));
    }

    @Test
    void aMissingDirectoryOrMetaFileIsReportedAndTheRestIsRead() {
        BacklogSnapshot snapshot = Fixtures.read("partial");

        assertEquals(List.of("DEMO-1"), Fixtures.keys(snapshot));
        assertEquals("DEMO", snapshot.keyPrefix(), "taken from the space name");
        assertEquals(List.of(), snapshot.sprints());
        assertEquals(List.of(
                "vault/demo/_backlog-meta.yaml: missing; key prefix taken from the space name as DEMO",
                "vault/demo/wiki/: directory is missing",
                "vault/demo/output/: directory is missing"), snapshot.problems(),
                "a space without sprints is normal and not reported");
    }

    @Test
    void aMissingSpaceIsUnavailableAndFailsTheReadAsAWhole() {
        var source = new VaultBacklogSource(Fixtures.vault("mapping"), "absent");

        assertFalse(source.available());
        assertThrows(BacklogSourceException.class, source::read);
    }

    @Test
    void aSpaceThatIsNotAPlainDirectoryNameIsRefused() {
        var root = Fixtures.vault("mapping");

        for (String space : List.of("../demo", "demo/raw", "Demo", "", ".")) {
            assertThrows(IllegalArgumentException.class, () -> new VaultBacklogSource(root, space), space);
        }
    }
}
