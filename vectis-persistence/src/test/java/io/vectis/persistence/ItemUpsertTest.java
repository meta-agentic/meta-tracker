package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Workspace;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * VEC-54 against PostgreSQL 16: the import's upsert. Every test reads the database back, because
 * what matters is which rows and events exist, not what the repository says it did.
 */
@QuarkusTest
class ItemUpsertTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject ItemRepository items;
    @Inject Pool pool;

    private ProvisionedWorkspace provisioned() {
        int n = SEQ.incrementAndGet();
        return workspaces.provision(Workspace.create("UP" + n, "Upsert " + n), null, TemplateRef.parse("scrum@1"), null)
                .await().indefinitely();
    }

    private static ImportedItem item(String key, String title, String column) {
        return new ImportedItem(key, title, column, "000" + key.substring(key.indexOf('-') + 1),
                Map.of("type", "story"));
    }

    private ImportResult upsert(ProvisionedWorkspace p, ImportedItem... in) {
        return items.upsertAll(p.board().id(), List.of(in), "import").await().indefinitely();
    }

    private Map<String, Row> rows(ProvisionedWorkspace p) {
        var out = new java.util.TreeMap<String, Row>();
        pool.preparedQuery("""
                        select i.id, i.key, i.title, i.rank, i.version, i.fields::text as fields, c.key as column_key
                          from item i join board_column c on c.id = i.column_id
                         where i.workspace_id = $1
                        """)
                .execute(Tuple.of(p.workspace().id())).await().indefinitely()
                .forEach(row -> out.put(row.getString("key"), row));
        return out;
    }

    private List<String> events(ProvisionedWorkspace p) {
        var out = new ArrayList<String>();
        pool.preparedQuery("select type, payload::text as payload from workspace_event where workspace_id = $1 order by seq")
                .execute(Tuple.of(p.workspace().id())).await().indefinitely()
                .forEach(row -> out.add(row.getString("type") + " " + row.getString("payload")));
        return out;
    }

    @Test
    void itemsAreCreatedInTheColumnTheyNameAndOneBulkEventSaysSo() {
        ProvisionedWorkspace p = provisioned();

        ImportResult result = upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two", "done"),
                item("UP-3", "Three", "no-go"));

        assertEquals(new ImportResult(3, 0, 0), result);
        Map<String, Row> stored = rows(p);
        assertEquals(List.of("UP-1", "UP-2", "UP-3"), List.copyOf(stored.keySet()));
        assertEquals("backlog", stored.get("UP-1").getString("column_key"));
        assertEquals("done", stored.get("UP-2").getString("column_key"));
        assertEquals("no-go", stored.get("UP-3").getString("column_key"));
        assertEquals(1L, stored.get("UP-1").getLong("version"));
        assertEquals(1, events(p).size());
        assertEquals(true, events(p).getFirst().startsWith("resync "));
    }

    @Test
    void runningTheSameImportAgainWritesNothing() {
        ProvisionedWorkspace p = provisioned();
        upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two", "todo"));
        Map<String, Row> before = rows(p);

        ImportResult again = upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two", "todo"));

        assertEquals(new ImportResult(0, 0, 2), again);
        Map<String, Row> after = rows(p);
        for (String key : before.keySet()) {
            assertEquals(before.get(key).getUUID("id"), after.get(key).getUUID("id"), key);
            assertEquals(before.get(key).getLong("version"), after.get(key).getLong("version"), key);
        }
        assertEquals(1, events(p).size(), "a run that wrote nothing appends no event");
    }

    @Test
    void anEditedItemUpdatesItsOwnRowAndNothingElse() {
        ProvisionedWorkspace p = provisioned();
        upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two", "todo"), item("UP-3", "Three", "done"));
        Map<String, Row> before = rows(p);

        ImportResult result = upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two, renamed", "todo"),
                item("UP-3", "Three", "done"));

        assertEquals(new ImportResult(0, 1, 2), result);
        Map<String, Row> after = rows(p);
        assertEquals(3, after.size(), "no item is duplicated");
        assertEquals(before.get("UP-2").getUUID("id"), after.get("UP-2").getUUID("id"), "the same item");
        assertEquals("Two, renamed", after.get("UP-2").getString("title"));
        assertEquals(2L, after.get("UP-2").getLong("version"));
        assertEquals(1L, after.get("UP-1").getLong("version"));
        assertEquals(1L, after.get("UP-3").getLong("version"));
        assertEquals(before.get("UP-2").getString("rank"), after.get("UP-2").getString("rank"));
        assertEquals(2, events(p).size());
    }

    @Test
    void anItemWhoseStatusMovedItIsMovedToItsNewColumn() {
        ProvisionedWorkspace p = provisioned();
        upsert(p, item("UP-1", "One", "in-progress"));

        ImportResult result = upsert(p, item("UP-1", "One", "done"));

        assertEquals(new ImportResult(0, 1, 0), result);
        assertEquals("done", rows(p).get("UP-1").getString("column_key"));
    }

    @Test
    void aChangedFieldIsAnUpdateAndFieldOrderIsNot() {
        ProvisionedWorkspace p = provisioned();
        var first = new ImportedItem("UP-1", "One", "backlog", "00000001", Map.of("type", "story", "priority", "P1"));
        var same = new ImportedItem("UP-1", "One", "backlog", "00000001", Map.of("priority", "P1", "type", "story"));
        var changed = new ImportedItem("UP-1", "One", "backlog", "00000001", Map.of("type", "story", "priority", "P0"));
        upsert(p, first);

        assertEquals(new ImportResult(0, 0, 1), upsert(p, same));
        assertEquals(new ImportResult(0, 1, 0), upsert(p, changed));
        assertEquals(true, rows(p).get("UP-1").getString("fields").contains("P0"));
    }

    @Test
    void anItemTheImportDoesNotNameIsLeftAlone() {
        ProvisionedWorkspace p = provisioned();
        upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two", "backlog"));

        assertEquals(new ImportResult(0, 0, 1), upsert(p, item("UP-1", "One", "backlog")));

        assertEquals(2, rows(p).size());
    }

    @Test
    void aColumnThatIsNotOnTheBoardFailsTheWholeImportAndWritesNothing() {
        ProvisionedWorkspace p = provisioned();

        Throwable failure = assertThrows(Throwable.class,
                () -> upsert(p, item("UP-1", "One", "backlog"), item("UP-2", "Two", "no-such-column")));

        assertInstanceOf(PlacementException.class, failure);
        assertEquals(0, rows(p).size());
        assertEquals(0, events(p).size());
    }

    @Test
    void anItemKeyNamedTwiceIsRefused() {
        ProvisionedWorkspace p = provisioned();

        assertThrows(IllegalArgumentException.class,
                () -> upsert(p, item("UP-1", "One", "backlog"), item("UP-1", "Again", "done")));
        assertEquals(0, rows(p).size());
    }

    @Test
    void theSameKeyInTwoWorkspacesIsTwoItems() {
        ProvisionedWorkspace a = provisioned();
        ProvisionedWorkspace b = provisioned();

        upsert(a, item("UP-1", "In a", "backlog"));
        upsert(b, item("UP-1", "In b", "backlog"));

        assertEquals("In a", rows(a).get("UP-1").getString("title"));
        assertEquals("In b", rows(b).get("UP-1").getString("title"));
    }

    @Test
    void anImportIntoABoardThatDoesNotExistIsRefused() {
        assertThrows(PlacementException.class, () -> items.upsertAll(UUID.randomUUID(),
                List.of(item("UP-1", "One", "backlog")), null).await().indefinitely());
    }
}
