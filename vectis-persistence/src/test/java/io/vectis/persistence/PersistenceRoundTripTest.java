package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.TimeOrderedId;
import io.vectis.domain.Workspace;
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
 * Exercises the schema and repositories against a real PostgreSQL. Every assertion here
 * concerns behaviour a mock could not have reported: that jsonb survives a round trip
 * with its types intact, that PostgreSQL's bytewise uuid ordering agrees with UUIDv7's
 * layout, and that a GIN containment query actually matches.
 */
@QuarkusTest
class PersistenceRoundTripTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;
    @Inject Pool pool;

    /** Workspace keys are globally unique, so each test mints its own. */
    private Workspace newWorkspace() {
        String suffix = Integer.toString(KEY_SEQ.incrementAndGet());
        return workspaces.insert(Workspace.create("WS" + suffix, "Workspace " + suffix))
                .await().indefinitely();
    }

    private Board newBoard(Workspace ws) {
        Board board = Board.create(ws.id(), "Delivery", List.of(
                BoardColumn.create("Backlog", 0),
                BoardColumn.create("In Progress", 1),
                BoardColumn.create("Done", 2)));
        return boards.insert(board).await().indefinitely();
    }

    @Test
    void migrationsBuildTheSchemaFromEmpty() {
        List<String> tables = pool.query("""
                        select table_name from information_schema.tables
                         where table_schema = 'public' order by table_name
                        """)
                .execute().await().indefinitely()
                .stream().map(row -> row.getString("table_name")).toList();

        assertTrue(tables.containsAll(List.of("workspace", "board", "board_column", "item")),
                "migrated tables: " + tables);
    }

    @Test
    void workspaceRoundTrips() {
        Workspace saved = newWorkspace();

        assertEquals(saved, workspaces.findById(saved.id()).await().indefinitely().orElseThrow());
        assertEquals(saved, workspaces.findByKey(saved.key()).await().indefinitely().orElseThrow());
    }

    @Test
    void boardRoundTripsWithItsColumnsInOrder() {
        Workspace ws = newWorkspace();
        Board saved = newBoard(ws);

        Board loaded = boards.findById(saved.id()).await().indefinitely().orElseThrow();

        assertEquals(saved.id(), loaded.id());
        assertEquals(ws.id(), loaded.workspaceId());
        assertEquals(List.of("Backlog", "In Progress", "Done"),
                loaded.orderedColumns().stream().map(BoardColumn::name).toList());
    }

    @Test
    void itemRoundTripsWithItsOpenFieldSetIntact() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        UUID backlog = board.orderedColumns().getFirst().id();

        Item item = Item.create(ws.id(), board.id(), backlog, ws.key() + "-1", "First item", "m")
                .withFields(Map.of(
                        "storyPoints", 5,
                        "epic", "PROJ-1",
                        "labels", List.of("dogfood", "persistence")));
        items.insert(item).await().indefinitely();

        Item loaded = items.findById(item.id()).await().indefinitely().orElseThrow();

        assertEquals("First item", loaded.title());
        assertEquals(5, loaded.fields().get("storyPoints"));
        assertEquals("PROJ-1", loaded.fields().get("epic"));
        assertEquals(List.of("dogfood", "persistence"), loaded.fields().get("labels"));
    }

    @Test
    void boardReadReturnsItemsByColumnThenRank() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        List<BoardColumn> cols = board.orderedColumns();

        // Deliberately inserted out of display order.
        items.insertAll(List.of(
                        Item.create(ws.id(), board.id(), cols.get(2).id(), ws.key() + "-3", "done", "m"),
                        Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-2", "second", "s"),
                        Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-1", "first", "e")))
                .await().indefinitely();

        assertEquals(List.of("first", "second", "done"),
                items.findByBoard(board.id()).await().indefinitely().stream().map(Item::title).toList());
    }

    @Test
    void movingACardChangesOnlyItsColumnAndRank() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        List<BoardColumn> cols = board.orderedColumns();

        Item item = items.insert(
                        Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-1", "drifting", "m"))
                .await().indefinitely();

        items.move(item, cols.get(1).id(), "d").await().indefinitely();

        Item loaded = items.findById(item.id()).await().indefinitely().orElseThrow();
        assertEquals(cols.get(1).id(), loaded.columnId());
        assertEquals("d", loaded.rank());
        assertEquals("drifting", loaded.title());
        assertEquals(item.key(), loaded.key());
    }

    /**
     * The claim the identity benchmark was run to justify: because the key is time-ordered, ordering by
     * the primary key <em>is</em> chronological ordering. If PostgreSQL's uuid ordering
     * disagreed with UUIDv7's layout this would fail, and every "newest first" query in
     * the product would silently need a {@code created_at} index instead.
     */
    @Test
    void primaryKeyOrderMatchesCreationOrder() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        UUID col = board.orderedColumns().getFirst().id();

        List<Item> created = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            created.add(Item.create(ws.id(), board.id(), col, ws.key() + "-" + i, "item " + i, "m" + i));
        }
        items.insertAll(created).await().indefinitely();

        List<UUID> byPrimaryKey = pool.preparedQuery(
                        "select id from item where workspace_id = $1 order by id")
                .execute(Tuple.of(ws.id()))
                .await().indefinitely()
                .stream().map(row -> row.getUUID("id")).toList();

        assertEquals(created.stream().map(Item::id).toList(), byPrimaryKey,
                "database key order must match creation order");
        assertFalse(TimeOrderedId.timestampOf(byPrimaryKey.getLast())
                        .isBefore(TimeOrderedId.timestampOf(byPrimaryKey.getFirst())),
                "last item must not predate the first");
    }

    @Test
    void containmentQueryMatchesOnTheOpenFieldSet() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        UUID col = board.orderedColumns().getFirst().id();

        items.insert(Item.create(ws.id(), board.id(), col, ws.key() + "-1", "in epic", "a")
                .withFields(Map.of("epic", "PROJ-1"))).await().indefinitely();
        items.insert(Item.create(ws.id(), board.id(), col, ws.key() + "-2", "other epic", "b")
                .withFields(Map.of("epic", "PROJ-2"))).await().indefinitely();

        assertEquals(List.of("in epic"),
                items.findByFieldsContaining(ws.id(), Map.of("epic", "PROJ-1"))
                        .await().indefinitely().stream().map(Item::title).toList());
    }
}
