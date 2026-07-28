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

    private final Pool pool;

    public ItemRepository(Pool pool) {
        this.pool = pool;
    }

    public Uni<Item> insert(Item item) {
        return pool.preparedQuery("""
                        insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields)
                        values ($1, $2, $3, $4, $5, $6, $7, $8)
                        """)
                .execute(bind(item))
                .replaceWith(item);
    }

    /** {@code Tuple.of} tops out at six values, so the eight-column bind goes through a list. */
    private static Tuple bind(Item item) {
        return Tuple.from(List.of(
                item.id(),
                item.workspaceId(),
                item.boardId(),
                item.columnId(),
                item.key(),
                item.title(),
                item.rank(),
                new JsonObject(item.fields())));
    }

    /** Inserts many items in a single round trip — the import path (VEC-18/19). */
    public Uni<Integer> insertAll(List<Item> items) {
        if (items.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        List<Tuple> batch = items.stream().map(ItemRepository::bind).toList();
        return pool.withTransaction(conn -> conn.preparedQuery("""
                        insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields)
                        values ($1, $2, $3, $4, $5, $6, $7, $8)
                        """)
                .executeBatch(batch)
                .replaceWith(items.size()));
    }

    public Uni<Optional<Item>> findById(UUID id) {
        return pool.preparedQuery(
                        "select id, workspace_id, board_id, column_id, key, title, rank, fields from item where id = $1")
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
        return pool.preparedQuery("""
                        select id, workspace_id, board_id, column_id, key, title, rank, fields
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
                               i.key, i.title, i.rank, i.fields
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

    /** Items whose open field set contains the given fragment (a GIN containment hit). */
    public Uni<List<Item>> findByFieldsContaining(UUID workspaceId, Map<String, Object> fragment) {
        return pool.preparedQuery("""
                        select id, workspace_id, board_id, column_id, key, title, rank, fields
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
                fields == null ? Map.of() : fields.getMap());
    }
}
