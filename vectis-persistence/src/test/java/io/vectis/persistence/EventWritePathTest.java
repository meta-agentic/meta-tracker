package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.Sprint;
import io.vectis.domain.TimeOrderedId;
import io.vectis.domain.Workspace;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The write path of the real-time transport: every mutating repository method appends exactly
 * one event, in the change's own transaction, at the workspace's next {@code seq}, with the
 * item's {@code version} raised; and a write that fails leaves neither its change nor its event.
 *
 * <p>"The same transaction" is checked directly rather than inferred: the event row, the
 * workspace row whose head it moved, and the item row it describes carry the same {@code xmin},
 * the id of the transaction that last wrote them.
 */
@QuarkusTest
class EventWritePathTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;
    @Inject SprintRepository sprints;
    @Inject Pool pool;

    record LoggedEvent(long seq, String type, JsonObject payload, String xmin) {}

    private Workspace newWorkspace() {
        String suffix = Integer.toString(KEY_SEQ.incrementAndGet());
        return workspaces.insert(Workspace.create("EV" + suffix, "Workspace " + suffix)).await().indefinitely();
    }

    private Board newBoard(Workspace ws) {
        return boards.insert(Board.create(ws.id(), "Delivery", List.of(
                        BoardColumn.create("Backlog", 0),
                        BoardColumn.create("In Progress", 1))))
                .await().indefinitely();
    }

    private List<LoggedEvent> events(Workspace ws) {
        List<LoggedEvent> out = new ArrayList<>();
        pool.preparedQuery("""
                        select seq, type, payload, xmin::text as xmin
                          from workspace_event where workspace_id = $1 order by seq
                        """)
                .execute(Tuple.of(ws.id())).await().indefinitely()
                .forEach(row -> out.add(new LoggedEvent(row.getLong("seq"), row.getString("type"),
                        new JsonObject(row.getString("payload")), row.getString("xmin"))));
        return out;
    }

    private long head(Workspace ws) {
        return pool.preparedQuery("select event_seq from workspace where id = $1")
                .execute(Tuple.of(ws.id())).await().indefinitely().iterator().next().getLong("event_seq");
    }

    private String xmin(String table, UUID id) {
        return pool.preparedQuery("select xmin::text as xmin from " + table + " where id = $1")
                .execute(Tuple.of(id)).await().indefinitely().iterator().next().getString("xmin");
    }

    private long storedVersion(Item item) {
        return items.findById(item.id()).await().indefinitely().orElseThrow().version();
    }

    /**
     * Asserts that the last write appended exactly one event after {@code before}, of
     * {@code type}, at the next seq, in the same transaction as the workspace head it moved.
     */
    private LoggedEvent assertOneEventAppended(Workspace ws, List<LoggedEvent> before, String type) {
        List<LoggedEvent> after = events(ws);
        assertEquals(before.size() + 1, after.size(), "exactly one event per write: " + after);
        LoggedEvent event = after.getLast();
        assertEquals(before.size() + 1L, event.seq(), "seq is gap-free from 1");
        assertEquals(event.seq(), head(ws), "the workspace head is the last event's seq");
        assertEquals(type, event.type());
        assertEquals(type, event.payload().getString("type"));
        assertEquals(event.seq(), event.payload().getLong("seq"));
        assertEquals(ws.id().toString(), event.payload().getString("workspaceId"));
        assertEquals(xmin("workspace", ws.id()), event.xmin(), "event and head move in one transaction");
        return event;
    }

    private void assertDescribes(LoggedEvent event, Item item) {
        JsonObject described = event.payload().getJsonObject("item");
        assertEquals(item.id().toString(), described.getString("id"));
        assertEquals(item.version(), described.getLong("version"));
        assertEquals(item.version(), storedVersion(item), "the event carries the stored version");
        assertEquals(xmin("item", item.id()), event.xmin(), "event and change commit in one transaction");
    }

    @Test
    void everyMutatingMethodAppendsExactlyOneEventAndRaisesTheVersion() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        List<BoardColumn> cols = board.orderedColumns();

        Item created = items.insert(Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-1", "Draft", "m")
                        .withFields(Map.of("storyPoints", 3)))
                .await().indefinitely();
        LoggedEvent e1 = assertOneEventAppended(ws, List.of(), "item.created");
        assertEquals(1, created.version());
        assertDescribes(e1, created);

        Item edited = items.edit(created.withTitle("Final").withFields(Map.of("storyPoints", 5, "epic", "E-1")))
                .await().indefinitely();
        LoggedEvent e2 = assertOneEventAppended(ws, List.of(e1), "item.updated");
        assertEquals(2, edited.version());
        assertDescribes(e2, edited);
        assertEquals(List.of("title", "fields.epic", "fields.storyPoints"),
                e2.payload().getJsonArray("changed").getList());
        assertEquals("Final", e2.payload().getJsonObject("item").getString("title"));

        Item moved = items.move(edited, cols.get(1).id(), "d").await().indefinitely();
        LoggedEvent e3 = assertOneEventAppended(ws, List.of(e1, e2), "item.moved");
        assertEquals(3, moved.version());
        assertDescribes(e3, moved);
        assertEquals(cols.get(1).id().toString(), e3.payload().getJsonObject("item").getString("columnId"));

        // Writes that are not item changes append nothing: the event contract has no sprint events.
        Sprint sprint = sprints.start(sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely()
                .start()).await().indefinitely();
        assertEquals(3, events(ws).size(), "a sprint insert or start is not an item change");

        Item assigned = items.moveToSprint(moved, sprint.id()).await().indefinitely();
        LoggedEvent e4 = assertOneEventAppended(ws, List.of(e1, e2, e3), "item.updated");
        assertEquals(4, assigned.version());
        assertDescribes(e4, assigned);
        assertEquals(List.of("sprintId"), e4.payload().getJsonArray("changed").getList());

        sprints.complete(sprint.complete(), List.of(assigned.id()), null).await().indefinitely();
        LoggedEvent e5 = assertOneEventAppended(ws, List.of(e1, e2, e3, e4), "item.updated");
        Item returned = items.findById(assigned.id()).await().indefinitely().orElseThrow();
        assertEquals(5, returned.version());
        assertNull(returned.sprintId());
        assertDescribes(e5, returned);
        assertEquals(xmin("sprint", sprint.id()), e5.xmin(), "the completion and its item moves are one transaction");

        items.delete(returned).await().indefinitely();
        LoggedEvent e6 = assertOneEventAppended(ws, List.of(e1, e2, e3, e4, e5), "item.deleted");
        assertEquals(new JsonObject().put("id", returned.id().toString()).put("key", returned.key()).put("version", 6),
                e6.payload().getJsonObject("item"), "the tombstone is one version above the last representation");
        assertTrue(items.findById(returned.id()).await().indefinitely().isEmpty());
    }

    @Test
    void theEnvelopeHasTheContractsKeysInWireOrder() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);

        items.insert(Item.create(ws.id(), board.id(), board.orderedColumns().getFirst().id(), ws.key() + "-1", "A", "m"),
                "tab-7f3a").await().indefinitely();

        JsonObject payload = events(ws).getFirst().payload();
        assertEquals(List.of("type", "workspaceId", "seq", "at", "origin", "item", "changed"),
                List.copyOf(payload.fieldNames()));
        assertEquals(List.of("id", "key", "boardId", "columnId", "title", "rank", "fields", "sprintId", "version",
                        "updatedAt"),
                List.copyOf(payload.getJsonObject("item").fieldNames()));
        assertEquals("tab-7f3a", payload.getString("origin"));
        assertTrue(payload.getString("at").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{6}Z"),
                "UTC, microseconds: " + payload.getString("at"));
        assertEquals(payload.getString("at"), payload.getJsonObject("item").getString("updatedAt"));
    }

    @Test
    void anOriginThatIsNotAnOpaqueTagIsRecordedAsNull() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);

        items.insert(Item.create(ws.id(), board.id(), board.orderedColumns().getFirst().id(), ws.key() + "-1", "A", "m"),
                "tab 7f3a\"}").await().indefinitely();

        assertNull(events(ws).getFirst().payload().getString("origin"));
    }

    @Test
    void aBulkInsertAppendsOneResync() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        UUID col = board.orderedColumns().getFirst().id();

        int inserted = items.insertAll(List.of(
                        Item.create(ws.id(), board.id(), col, ws.key() + "-1", "one", "a"),
                        Item.create(ws.id(), board.id(), col, ws.key() + "-2", "two", "b"),
                        Item.create(ws.id(), board.id(), col, ws.key() + "-3", "three", "c")))
                .await().indefinitely();

        assertEquals(3, inserted);
        LoggedEvent event = assertOneEventAppended(ws, List.of(), "resync");
        assertEquals("bulk", event.payload().getString("reason"));
    }

    @Test
    void aFailedChangeAppendsNoEvent() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Item item = items.insert(Item.create(ws.id(), board.id(), board.orderedColumns().getFirst().id(),
                ws.key() + "-1", "A", "m")).await().indefinitely();
        List<LoggedEvent> before = events(ws);

        // The sprint does not exist: the item update violates its foreign key.
        assertThrows(Throwable.class,
                () -> items.moveToSprint(item, TimeOrderedId.next()).await().indefinitely());

        assertEquals(before, events(ws), "no event without its change");
        assertEquals(1, head(ws));
        assertEquals(1, storedVersion(item));
    }

    @Test
    void aFailedAppendRollsTheChangeBack() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        List<BoardColumn> cols = board.orderedColumns();
        Item item = items.insert(Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-1", "A", "m"))
                .await().indefinitely();

        // Occupy the next seq behind the write path's back, so appending the move's event
        // fails on the log's primary key after the move itself has been applied.
        pool.preparedQuery("insert into workspace_event (workspace_id, seq, type, payload) values ($1, 2, 'x', '{}')")
                .execute(Tuple.of(ws.id())).await().indefinitely();

        assertThrows(Throwable.class, () -> items.move(item, cols.get(1).id(), "d").await().indefinitely());

        Item stored = items.findById(item.id()).await().indefinitely().orElseThrow();
        assertEquals(cols.get(0).id(), stored.columnId(), "no change without its event");
        assertEquals(1, stored.version());
        assertEquals(1, head(ws));
    }

    @Test
    void aWriteNamingAnotherWorkspaceIsRefusedAndAppendsNothing() {
        Workspace home = newWorkspace();
        Workspace other = newWorkspace();
        Board board = newBoard(home);
        Item item = items.insert(Item.create(home.id(), board.id(), board.orderedColumns().getFirst().id(),
                home.key() + "-1", "A", "m")).await().indefinitely();
        Item misfiled = new Item(item.id(), other.id(), item.boardId(), item.columnId(), item.key(), item.title(),
                item.rank(), item.fields(), item.sprintId(), item.version());

        Throwable failure = assertThrows(Throwable.class,
                () -> items.move(misfiled, item.columnId(), "z").await().indefinitely());

        assertInstanceOf(ItemNotFoundException.class, failure);
        assertEquals(0, head(other));
        assertEquals(1, head(home));
        assertEquals("m", items.findById(item.id()).await().indefinitely().orElseThrow().rank());
    }

    @Test
    void aWriteToAnUnknownWorkspaceIsRefused() {
        Item orphan = Item.create(TimeOrderedId.next(), TimeOrderedId.next(), TimeOrderedId.next(), "NONE-1", "A", "m");

        assertThrows(WorkspaceNotFoundException.class, () -> items.insert(orphan).await().indefinitely());
    }

    @Test
    void theSnapshotCarriesTheCursorItsItemsAreCurrentTo() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        List<BoardColumn> cols = board.orderedColumns();
        Item item = items.insert(Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-1", "A", "m"))
                .await().indefinitely();
        Item moved = items.move(item, cols.get(1).id(), "d").await().indefinitely();

        WorkspaceSnapshot snapshot = items.snapshot(ws.id()).await().indefinitely().orElseThrow();

        UUID epoch = pool.preparedQuery("select stream_epoch from workspace where id = $1")
                .execute(Tuple.of(ws.id())).await().indefinitely().iterator().next().getUUID("stream_epoch");
        assertEquals(new StreamCursor(epoch, 2), snapshot.cursor());
        assertEquals(List.of(moved), snapshot.items());
        assertEquals(snapshot.cursor(), StreamCursor.parse(snapshot.cursor().toString()));
    }

    @Test
    void anEmptyWorkspaceSnapshotsAtSeqZeroAndAnUnknownOneIsEmpty() {
        Workspace ws = newWorkspace();

        WorkspaceSnapshot snapshot = items.snapshot(ws.id()).await().indefinitely().orElseThrow();

        assertEquals(0, snapshot.cursor().seq());
        assertNotNull(snapshot.cursor().epoch());
        assertTrue(snapshot.items().isEmpty());
        assertTrue(items.snapshot(TimeOrderedId.next()).await().indefinitely().isEmpty());
    }
}
