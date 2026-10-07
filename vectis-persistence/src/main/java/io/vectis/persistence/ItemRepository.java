package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Item;
import io.vectis.persistence.WorkspaceEventLog.Change;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-blocking reads and writes for {@link Item}.
 *
 * <p>Every write goes through {@link WorkspaceEventLog#write}: it locks the item's workspace,
 * raises the item's {@code version}, and appends the event describing the change in the same
 * transaction. Each write method has a variant taking the writer's {@code Vectis-Origin} tag,
 * which the event carries; the variant without one records {@code null}. Every write is
 * scoped to {@code item.workspaceId()}: an item that is not in that workspace is
 * {@link ItemNotFoundException}, never a silent no-op.
 */
@ApplicationScoped
public class ItemRepository {

    static final String SELECT_COLUMNS =
            "id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id, version";

    private static final String INSERT = """
            insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id)
            values ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            """;

    private final Pool pool;
    private final WorkspaceEventLog log;

    public ItemRepository(Pool pool, WorkspaceEventLog log) {
        this.pool = pool;
        this.log = log;
    }

    public Uni<Item> insert(Item item) {
        return insert(item, null);
    }

    /** Creates the item at version 1 and appends {@code item.created}. */
    public Uni<Item> insert(Item item, String origin) {
        return log.write(item.workspaceId(), origin, scope -> scope.connection()
                .preparedQuery(INSERT + " returning " + SELECT_COLUMNS)
                .execute(bind(item))
                .map(rows -> {
                    Item saved = map(rows.iterator().next());
                    return Change.of(saved, ItemEvents.created(saved, scope.at()));
                }));
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

    public Uni<Integer> insertAll(List<Item> items) {
        return insertAll(items, null);
    }

    /**
     * Inserts many items of one workspace in a single round trip — the import path. One
     * {@code resync} event with reason {@code bulk} describes the whole batch: watching
     * clients reload once instead of applying one event per row.
     *
     * <p>Fails with {@link IllegalArgumentException} if the items span more than one workspace.
     */
    public Uni<Integer> insertAll(List<Item> items, String origin) {
        if (items.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        UUID workspaceId = items.getFirst().workspaceId();
        if (items.stream().anyMatch(i -> !i.workspaceId().equals(workspaceId))) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "a bulk insert writes into one workspace's stream; items span several"));
        }
        List<Tuple> batch = items.stream().map(ItemRepository::bind).toList();
        return log.write(workspaceId, origin, scope -> scope.connection()
                .preparedQuery(INSERT)
                .executeBatch(batch)
                .replaceWith(Change.of(items.size(), ItemEvents.bulk())));
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
                               i.key, i.title, i.rank, i.fields, i.sprint_id, i.version
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
        return move(item, targetColumnId, newRank, null);
    }

    /** {@link #move(Item, UUID, String)}, appending {@code item.moved}. */
    public Uni<Item> move(Item item, UUID targetColumnId, String newRank, String origin) {
        return log.write(item.workspaceId(), origin, scope -> scope.connection()
                .preparedQuery("""
                        update item set column_id = $1, rank = $2, version = version + 1, updated_at = now()
                         where id = $3 and workspace_id = $4
                        returning\s""" + SELECT_COLUMNS)
                .execute(Tuple.of(targetColumnId, newRank, item.id(), item.workspaceId()))
                .map(rows -> {
                    Item moved = single(rows, item);
                    return Change.of(moved, ItemEvents.moved(moved, scope.at()));
                }));
    }

    /**
     * Assigns an item to a sprint, or returns it to the backlog if {@code sprintId} is
     * null. Mirrors {@link #move} for column moves: one row written regardless of scope.
     */
    public Uni<Item> moveToSprint(Item item, UUID sprintId) {
        return moveToSprint(item, sprintId, null);
    }

    /** {@link #moveToSprint(Item, UUID)}, appending {@code item.updated}. */
    public Uni<Item> moveToSprint(Item item, UUID sprintId, String origin) {
        return log.write(item.workspaceId(), origin, scope -> scope.connection()
                .preparedQuery("""
                        update item set sprint_id = $1, version = version + 1, updated_at = now()
                         where id = $2 and workspace_id = $3
                        returning\s""" + SELECT_COLUMNS)
                .execute(Tuple.of(sprintId, item.id(), item.workspaceId()))
                .map(rows -> {
                    Item moved = single(rows, item);
                    return Change.of(moved, ItemEvents.sprintAssigned(moved, scope.at()));
                }));
    }

    public Uni<Item> edit(Item edited) {
        return edit(edited, null);
    }

    /**
     * Writes the item's editable attributes, its {@code title} and open {@code fields}, as
     * {@code edited} holds them, and appends {@code item.updated} naming what changed against
     * the row as it was. Column, rank and sprint have their own writes.
     */
    public Uni<Item> edit(Item edited, String origin) {
        return log.write(edited.workspaceId(), origin, scope -> scope.connection()
                .preparedQuery("""
                        update item i
                           set title = $1, fields = $2, version = i.version + 1, updated_at = now()
                          from item old
                         where old.id = i.id and i.id = $3 and i.workspace_id = $4
                        returning i.id, i.workspace_id, i.board_id, i.column_id, i.key, i.title, i.rank,
                                  i.fields, i.sprint_id, i.version, old.title as old_title, old.fields as old_fields
                        """)
                .execute(Tuple.of(edited.title(), new JsonObject(edited.fields()), edited.id(), edited.workspaceId()))
                .map(rows -> {
                    Item saved = single(rows, edited);
                    Row row = rows.iterator().next();
                    JsonObject oldFields = row.getJsonObject("old_fields");
                    return Change.of(saved, ItemEvents.edited(
                            saved, row.getString("old_title"), oldFields == null ? Map.of() : oldFields.getMap(),
                            scope.at()));
                }));
    }

    public Uni<Void> delete(Item item) {
        return delete(item, null);
    }

    /**
     * Deletes the item and appends {@code item.deleted}, a tombstone carrying the version
     * the deletion takes the item to, one above its last.
     */
    public Uni<Void> delete(Item item, String origin) {
        return log.write(item.workspaceId(), origin, scope -> scope.connection()
                .preparedQuery("delete from item where id = $1 and workspace_id = $2 returning key, version")
                .execute(Tuple.of(item.id(), item.workspaceId()))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        throw new ItemNotFoundException(item.workspaceId(), item.id());
                    }
                    Row row = rows.iterator().next();
                    return Change.<Void>of(null, ItemEvents.deleted(
                            item.id(), row.getString("key"), row.getLong("version") + 1));
                }));
    }

    /**
     * The workspace's items and the {@code <epoch>.<seq>} they are current to, read in one
     * statement: the same database snapshot answers both, so the cursor names exactly the
     * events the items reflect. Empty if the workspace does not exist.
     */
    public Uni<Optional<WorkspaceSnapshot>> snapshot(UUID workspaceId) {
        return pool.preparedQuery("""
                        select w.event_seq, w.stream_epoch,
                               i.id, i.workspace_id, i.board_id, i.column_id, i.key, i.title, i.rank,
                               i.fields, i.sprint_id, i.version
                          from workspace w
                          left join item i on i.workspace_id = w.id
                         where w.id = $1
                         order by i.id
                        """)
                .execute(Tuple.of(workspaceId))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        return Optional.<WorkspaceSnapshot>empty();
                    }
                    StreamCursor cursor = null;
                    List<Item> out = new ArrayList<>(rows.size());
                    for (Row row : rows) {
                        cursor = new StreamCursor(row.getUUID("stream_epoch"), row.getLong("event_seq"));
                        if (row.getUUID("id") != null) { // left join: an empty workspace yields one null row
                            out.add(map(row));
                        }
                    }
                    return Optional.of(new WorkspaceSnapshot(workspaceId, cursor, out));
                });
    }

    /** The one row an item write returns, or {@link ItemNotFoundException} if it matched none. */
    private static Item single(RowSet<Row> rows, Item target) {
        if (rows.rowCount() == 0) {
            throw new ItemNotFoundException(target.workspaceId(), target.id());
        }
        return map(rows.iterator().next());
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

    static List<Item> mapAll(RowSet<Row> rows) {
        List<Item> out = new ArrayList<>(rows.size());
        rows.forEach(row -> out.add(map(row)));
        return List.copyOf(out);
    }

    static Item map(Row row) {
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
                row.getUUID("sprint_id"),
                row.getLong("version"));
    }
}
