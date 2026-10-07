package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.Sprint;
import io.vectis.domain.SprintStatus;
import io.vectis.domain.Workspace;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the write path refuses, and the order it locks in: a write never lands in a stream that
 * does not hold its item, never overwrites a version it did not read, never writes when
 * nothing changes, and never touches an item row before it holds the workspace row.
 */
@QuarkusTest
class EventWriteGuardsTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;
    @Inject SprintRepository sprints;
    @Inject Pool pool;

    private Workspace newWorkspace() {
        String suffix = Integer.toString(KEY_SEQ.incrementAndGet());
        return workspaces.insert(Workspace.create("EG" + suffix, "Workspace " + suffix)).await().indefinitely();
    }

    private Board newBoard(Workspace ws) {
        return boards.insert(Board.create(ws.id(), "Delivery", List.of(
                        BoardColumn.create("Backlog", 0),
                        BoardColumn.create("In Progress", 1))))
                .await().indefinitely();
    }

    private Item newItem(Workspace ws, Board board, String suffix) {
        return items.insert(Item.create(ws.id(), board.id(), board.orderedColumns().getFirst().id(),
                ws.key() + "-" + suffix, "Item " + suffix, "m")).await().indefinitely();
    }

    private List<JsonObject> events(Workspace ws) {
        List<JsonObject> out = new ArrayList<>();
        pool.preparedQuery("select payload from workspace_event where workspace_id = $1 order by seq")
                .execute(Tuple.of(ws.id())).await().indefinitely()
                .forEach(row -> out.add(new JsonObject(row.getString("payload"))));
        return out;
    }

    private Item stored(Item item) {
        return items.findById(item.id()).await().indefinitely().orElseThrow();
    }

    private static Throwable failure(Runnable write) {
        return assertThrows(Throwable.class, write::run);
    }

    // --- placement: the stream is resolved from the database, never from the caller ---

    @Test
    void anItemOnABoardOfAnotherWorkspaceIsRefused() {
        Workspace home = newWorkspace();
        Workspace other = newWorkspace();
        Board otherBoard = newBoard(other);

        Item misplaced = Item.create(home.id(), otherBoard.id(), otherBoard.orderedColumns().getFirst().id(),
                home.key() + "-1", "A", "m");

        assertInstanceOf(PlacementException.class, failure(() -> items.insert(misplaced).await().indefinitely()));
        assertEquals(List.of(), events(home));
        assertEquals(List.of(), events(other));
    }

    @Test
    void aColumnOfAnotherBoardIsRefusedOnInsertAndOnMove() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Board otherBoard = newBoard(ws);
        UUID foreignColumn = otherBoard.orderedColumns().getFirst().id();

        assertInstanceOf(PlacementException.class, failure(() -> items.insert(
                Item.create(ws.id(), board.id(), foreignColumn, ws.key() + "-1", "A", "m")).await().indefinitely()));
        assertInstanceOf(PlacementException.class, failure(() -> items.insertAll(List.of(
                Item.create(ws.id(), board.id(), foreignColumn, ws.key() + "-2", "B", "m"))).await().indefinitely()));

        Item item = newItem(ws, board, "3");
        assertInstanceOf(PlacementException.class,
                failure(() -> items.move(item, foreignColumn, "z").await().indefinitely()));

        assertEquals(board.orderedColumns().getFirst().id(), stored(item).columnId());
        assertEquals(1, stored(item).version());
        assertEquals(1, events(ws).size(), "only the one successful insert appended");
    }

    @Test
    void aBulkInsertSpanningWorkspacesIsRefused() {
        Workspace a = newWorkspace();
        Workspace b = newWorkspace();
        Board boardA = newBoard(a);
        Board boardB = newBoard(b);

        Throwable refused = failure(() -> items.insertAll(List.of(
                        Item.create(a.id(), boardA.id(), boardA.orderedColumns().getFirst().id(), a.key() + "-1", "A", "m"),
                        Item.create(b.id(), boardB.id(), boardB.orderedColumns().getFirst().id(), b.key() + "-1", "B", "m")))
                .await().indefinitely());

        assertInstanceOf(IllegalArgumentException.class, refused);
        assertEquals(List.of(), events(a));
        assertEquals(List.of(), events(b));
    }

    // --- edits: If-Match, no-op, escaping ---

    @Test
    void anEditFromAStaleVersionConflictsAndWritesNothing() {
        Workspace ws = newWorkspace();
        Item read = newItem(ws, newBoard(ws), "1");

        items.edit(read.withTitle("First"), read.version(), "tab-a").await().indefinitely();
        Throwable refused = failure(() -> items.edit(read.withTitle("Second"), read.version(), "tab-b")
                .await().indefinitely());

        ItemVersionConflictException conflict = assertInstanceOf(ItemVersionConflictException.class, refused);
        assertEquals(1, conflict.expected());
        assertEquals(2, conflict.actual());
        assertEquals("First", stored(read).title(), "the first edit is not lost");
        assertEquals(2, events(ws).size(), "created, then the one edit that won");
    }

    @Test
    void anEditThatChangesNothingWritesNothing() {
        Workspace ws = newWorkspace();
        Item item = items.edit(newItem(ws, newBoard(ws), "1").withFields(Map.of("storyPoints", 3)), 1, null)
                .await().indefinitely();

        Item same = items.edit(item.withFields(Map.of("storyPoints", 3)), item.version(), null).await().indefinitely();

        assertEquals(item, same);
        assertEquals(2, stored(item).version());
        assertEquals(2, events(ws).size(), "no event, no seq for a no-op edit");
    }

    @Test
    void aFieldKeyWithADotIsEscapedInChanged() {
        Workspace ws = newWorkspace();
        Item item = newItem(ws, newBoard(ws), "1");

        items.edit(item.withFields(Map.of("a.b", 1, "c~d", 2)), item.version(), null).await().indefinitely();

        assertEquals(List.of("fields.a~1b", "fields.c~0d"),
                events(ws).getLast().getJsonArray("changed").getList());
    }

    @Test
    void writesToADeletedItemAreNotFound() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Item item = newItem(ws, board, "1");
        items.delete(item, null).await().indefinitely();
        int before = events(ws).size();

        assertInstanceOf(ItemNotFoundException.class,
                failure(() -> items.edit(item.withTitle("x"), 1, null).await().indefinitely()));
        assertInstanceOf(ItemNotFoundException.class,
                failure(() -> items.moveToSprint(item, null).await().indefinitely()));
        assertInstanceOf(ItemNotFoundException.class,
                failure(() -> items.move(item, board.orderedColumns().get(1).id(), "z").await().indefinitely()));
        assertInstanceOf(ItemNotFoundException.class, failure(() -> items.delete(item, null).await().indefinitely()));
        assertEquals(before, events(ws).size());
    }

    // --- sprint completion ---

    private Sprint active(Board board, String name) {
        return sprints.start(sprints.insert(Sprint.create(board.id(), name)).await().indefinitely().start())
                .await().indefinitely();
    }

    @Test
    void completingASprintAppendsOneEventPerMovedItemInDatabaseUuidOrder() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Sprint sprint = active(board, "Sprint 1");
        List<UUID> ids = new ArrayList<>();
        for (String s : List.of("1", "2", "3")) {
            ids.add(items.moveToSprint(newItem(ws, board, s), sprint.id()).await().indefinitely().id());
        }
        int before = events(ws).size();

        sprints.complete(sprint.complete(), List.of(ids.get(2), ids.get(0), ids.get(1)), null).await().indefinitely();

        List<JsonObject> appended = events(ws).subList(before, before + 3);
        assertEquals(before + 3, events(ws).size());
        List<String> dbOrder = pool.preparedQuery("select id::text as id from item where id = any($1) order by id")
                .execute(Tuple.of((Object) ids.toArray(UUID[]::new))).await().indefinitely()
                .stream().map(row -> row.getString("id")).toList();
        assertEquals(dbOrder, appended.stream().map(e -> e.getJsonObject("item").getString("id")).toList());
        for (int i = 0; i < 3; i++) {
            assertEquals(before + 1L + i, appended.get(i).getLong("seq"));
            assertEquals(3, appended.get(i).getJsonObject("item").getLong("version"));
        }
    }

    @Test
    void completingIntoASprintOfAnotherBoardOrACompletedOneIsRefused() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Board otherBoard = newBoard(ws);
        Sprint sprint = active(board, "Sprint 1");
        Sprint elsewhere = sprints.insert(Sprint.create(otherBoard.id(), "Elsewhere")).await().indefinitely();
        Item item = items.moveToSprint(newItem(ws, board, "1"), sprint.id()).await().indefinitely();
        int before = events(ws).size();

        assertInstanceOf(PlacementException.class, failure(() -> sprints.complete(
                sprint.complete(), List.of(item.id()), elsewhere.id()).await().indefinitely()));

        assertEquals(SprintStatus.ACTIVE, sprints.findById(sprint.id()).await().indefinitely().orElseThrow().status());
        assertEquals(sprint.id(), stored(item).sprintId());
        assertEquals(before, events(ws).size());

        Sprint doneOnBoard = sprints.insert(Sprint.create(board.id(), "Finished")).await().indefinitely();
        pool.preparedQuery("update sprint set status = 'COMPLETED', completed_at = now() where id = $1")
                .execute(Tuple.of(doneOnBoard.id())).await().indefinitely();
        assertInstanceOf(PlacementException.class, failure(() -> sprints.complete(
                sprint.complete(), List.of(item.id()), doneOnBoard.id()).await().indefinitely()));
        assertEquals(before, events(ws).size());
    }

    @Test
    void aSprintNamedWithTheWrongBoardIsNotCompleted() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Board otherBoard = newBoard(ws);
        Sprint sprint = active(board, "Sprint 1");
        Sprint misnamed = new Sprint(sprint.id(), otherBoard.id(), sprint.name(), SprintStatus.COMPLETED,
                sprint.startedAt(), Instant.now());

        assertInstanceOf(IllegalSprintTransitionException.class,
                failure(() -> sprints.complete(misnamed, List.of(), null).await().indefinitely()));
        assertEquals(SprintStatus.ACTIVE, sprints.findById(sprint.id()).await().indefinitely().orElseThrow().status());
    }

    // --- lock order ---

    /**
     * Lock-first, observed: while another transaction holds the workspace row, a writer must
     * wait for it <em>before</em> touching its item. Had the writer written the item first (and
     * taken the workspace row afterwards), it would now hold the item's row lock and the
     * {@code nowait} probe below would fail.
     */
    @Test
    @Timeout(30)
    void aWriterTakesTheWorkspaceRowBeforeAnyItemRow() throws Exception {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Item item = newItem(ws, board, "1");

        SqlConnection holder = pool.getConnection().await().indefinitely();
        CompletableFuture<Item> write;
        try {
            holder.query("begin").execute().await().indefinitely();
            holder.preparedQuery("select 1 from workspace where id = $1 for update")
                    .execute(Tuple.of(ws.id())).await().indefinitely();

            write = items.move(item, board.orderedColumns().get(1).id(), "z").subscribeAsCompletionStage();
            awaitABlockedWriter();

            SqlConnection probe = pool.getConnection().await().indefinitely();
            try {
                probe.query("begin").execute().await().indefinitely();
                probe.preparedQuery("select id from item where id = $1 for update nowait")
                        .execute(Tuple.of(item.id())).await().indefinitely(); // throws if the writer holds it
                probe.query("rollback").execute().await().indefinitely();
            } finally {
                probe.close().await().indefinitely();
            }
        } finally {
            holder.query("commit").execute().await().indefinitely();
            holder.close().await().indefinitely();
        }

        assertEquals(2, write.join().version(), "the writer proceeds once the workspace row is free");
    }

    private void awaitABlockedWriter() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            long waiting = pool.query("""
                            select count(*) as n from pg_stat_activity
                             where datname = current_database() and wait_event_type = 'Lock'
                            """)
                    .execute().await().indefinitely().iterator().next().getLong("n");
            if (waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(false, "the writer never waited for the workspace row");
    }
}
