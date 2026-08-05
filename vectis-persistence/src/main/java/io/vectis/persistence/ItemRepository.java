package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Item;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Non-blocking reads and writes for {@link Item}. */
@ApplicationScoped
public class ItemRepository {

    private static final String SELECT_COLUMNS =
            "id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id";

    private final Pool pool;

    public ItemRepository(Pool pool) {
        this.pool = pool;
    }

    public Uni<Item> insert(Item item) {
        return pool.preparedQuery("""
                        insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id)
                        values ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                        """)
                .execute(bind(item))
                .replaceWith(item);
    }

    /**
     * {@code Tuple.of} tops out at six values and {@code List.of} rejects the null
     * {@code sprintId} of a backlog item, so the nine-column bind goes through an array.
     */
    private static Tuple bind(Item item) {
        return Tuple.from(new Object[] {
            item.id(),
            item.workspaceId(),
            item.boardId(),
            item.columnId(),
            item.key(),
            item.title(),
            item.rank(),
            new JsonObject(item.fields()),
            item.sprintId()
        });
    }

    /** Inserts many items in a single round trip — the import path (VEC-18/19). */
    public Uni<Integer> insertAll(List<Item> items) {
        if (items.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        List<Tuple> batch = items.stream().map(ItemRepository::bind).toList();
        return pool.withTransaction(conn -> conn.preparedQuery("""
                        insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id)
                        values ($1, $2, $3, $4, $5, $6, $7, $8, $9)
                        """)
                .executeBatch(batch)
                .replaceWith(items.size()));
    }

    public Uni<Optional<Item>> findById(UUID id) {
        return pool.preparedQuery("select " + SELECT_COLUMNS + " from item where id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() == 0
                        ? Optional.<Item>empty()
                        : Optional.of(map(rows.iterator().next())));
    }

    /**
     * Every item in a column, in display order — the board read path, served by
     * {@code item_board_column_rank_idx} with no sort step.
     */
    public Uni<List<Item>> findByColumn(UUID boardId, UUID columnId) {
        return pool.preparedQuery("select " + SELECT_COLUMNS + """
                          from item
                         where board_id = $1 and column_id = $2
                         order by rank
                        """)
                .execute(Tuple.of(boardId, columnId))
                .map(ItemRepository::mapAll);
    }

    /** Every item on a board, ordered by column then rank — one query per board render. */
    public Uni<List<Item>> findByBoard(UUID boardId) {
        return pool.preparedQuery("""
                        select i.id, i.workspace_id, i.board_id, i.column_id,
                               i.key, i.title, i.rank, i.fields, i.sprint_id
                          from item i
                          join board_column c on c.id = i.column_id
                         where i.board_id = $1
                         order by c.ordinal, i.rank
                        """)
                .execute(Tuple.of(boardId))
                .map(ItemRepository::mapAll);
    }

    /**
     * Moves an item to a column at a rank. One row is written however far the card
     * travelled, which is the reason rank is a sparse string rather than an index.
     */
    public Uni<Item> move(Item item, UUID targetColumnId, String newRank) {
        Item moved = item.movedTo(targetColumnId, newRank);
        return pool.preparedQuery(
                        "update item set column_id = $1, rank = $2, updated_at = now() where id = $3")
                .execute(Tuple.of(targetColumnId, newRank, item.id()))
                .replaceWith(moved);
    }

    /**
     * Assigns an item to a sprint, or returns it to the backlog if {@code sprintId} is
     * null. Mirrors {@link #move} for column moves: one row written regardless of scope.
     */
    public Uni<Item> moveToSprint(Item item, UUID sprintId) {
        Item moved = item.withSprint(sprintId);
        return pool.preparedQuery("update item set sprint_id = $1, updated_at = now() where id = $2")
                .execute(Tuple.of(sprintId, item.id()))
                .replaceWith(moved);
    }

    /**
     * Items in a board's backlog ({@code sprintId} null) or in a specific sprint's
     * scope — the "backlog scope distinct from sprint scope" read path. {@code is not
     * distinct from} rather than {@code =} so the backlog (null) case matches.
     */
    public Uni<List<Item>> findByBoardScope(UUID boardId, UUID sprintId) {
        return pool.preparedQuery("select " + SELECT_COLUMNS + """
                          from item
                         where board_id = $1 and sprint_id is not distinct from $2
                         order by rank
                        """)
                .execute(Tuple.of(boardId, sprintId))
                .map(ItemRepository::mapAll);
    }

    /** Items whose open field set contains the given fragment (a GIN containment hit). */
    public Uni<List<Item>> findByFieldsContaining(UUID workspaceId, Map<String, Object> fragment) {
        return pool.preparedQuery("select " + SELECT_COLUMNS + """
                          from item
                         where workspace_id = $1 and fields @> $2
                         order by id
                        """)
                .execute(Tuple.of(workspaceId, new JsonObject(fragment)))
                .map(ItemRepository::mapAll);
    }

    private static List<Item> mapAll(io.vertx.mutiny.sqlclient.RowSet<Row> rows) {
        List<Item> out = new ArrayList<>(rows.size());
        rows.forEach(row -> out.add(map(row)));
        return List.copyOf(out);
    }

    private static Item map(Row row) {
        JsonObject fields = row.getJsonObject("fields");
        return new Item(
                row.getUUID("id"),
                row.getUUID("workspace_id"),
                row.getUUID("board_id"),
                row.getUUID("column_id"),
                row.getString("key"),
                row.getString("title"),
                row.getString("rank"),
                fields == null ? Map.of() : fields.getMap(),
                row.getUUID("sprint_id"));
    }
}
