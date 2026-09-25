package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Non-blocking reads and writes for {@link Board} and its columns. */
@ApplicationScoped
public class BoardRepository {

    private final Pool pool;

    public BoardRepository(Pool pool) {
        this.pool = pool;
    }

    /**
     * Inserts the board and all of its columns in one transaction.
     *
     * <p>A board with no columns is not a board — a partial insert would leave a
     * workspace holding something nothing can be dropped into, so the two statements
     * commit or fail together.
     */
    public Uni<Board> insert(Board board) {
        return pool.withTransaction(conn -> insertOn(conn, board));
    }

    /** The board and its columns on a connection the caller's transaction already holds. */
    static Uni<Board> insertOn(SqlConnection conn, Board board) {
        return conn.preparedQuery("insert into board (id, workspace_id, name) values ($1, $2, $3)")
                .execute(Tuple.of(board.id(), board.workspaceId(), board.name()))
                .chain(ignored -> {
                    List<Tuple> columns = board.orderedColumns().stream()
                            .map(c -> Tuple.of(c.id(), board.id(), c.name(), c.position()))
                            .toList();
                    if (columns.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    return conn.preparedQuery(
                                    "insert into board_column (id, board_id, name, ordinal) values ($1, $2, $3, $4)")
                            .executeBatch(columns)
                            .replaceWithVoid();
                })
                .replaceWith(board);
    }

    /** A board with its columns already in display order, or empty if unknown. */
    public Uni<Optional<Board>> findById(UUID id) {
        return pool.preparedQuery("""
                        select b.id           as board_id,
                               b.workspace_id as workspace_id,
                               b.name         as board_name,
                               c.id           as column_id,
                               c.name         as column_name,
                               c.ordinal      as column_ordinal
                          from board b
                          left join board_column c on c.board_id = b.id
                         where b.id = $1
                         order by c.ordinal
                        """)
                .execute(Tuple.of(id))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        return Optional.<Board>empty();
                    }
                    List<BoardColumn> columns = new ArrayList<>();
                    UUID workspaceId = null;
                    String name = null;
                    for (var row : rows) {
                        workspaceId = row.getUUID("workspace_id");
                        name = row.getString("board_name");
                        UUID columnId = row.getUUID("column_id");
                        if (columnId != null) { // left join: a column-less board yields one null row
                            columns.add(new BoardColumn(
                                    columnId, row.getString("column_name"), row.getInteger("column_ordinal")));
                        }
                    }
                    return Optional.of(new Board(id, workspaceId, name, columns));
                });
    }

    /**
     * Every board in a workspace with its columns, boards oldest first and columns in
     * display order. One query: the left join keeps a column-less board visible.
     */
    public Uni<List<Board>> findByWorkspace(UUID workspaceId) {
        return pool.preparedQuery("""
                        select b.id      as board_id,
                               b.name    as board_name,
                               c.id      as column_id,
                               c.name    as column_name,
                               c.ordinal as column_ordinal
                          from board b
                          left join board_column c on c.board_id = b.id
                         where b.workspace_id = $1
                         order by b.id, c.ordinal
                        """)
                .execute(Tuple.of(workspaceId))
                .map(rows -> {
                    Map<UUID, String> names = new LinkedHashMap<>();
                    Map<UUID, List<BoardColumn>> columns = new LinkedHashMap<>();
                    for (var row : rows) {
                        UUID boardId = row.getUUID("board_id");
                        names.putIfAbsent(boardId, row.getString("board_name"));
                        List<BoardColumn> boardColumns = columns.computeIfAbsent(boardId, id -> new ArrayList<>());
                        UUID columnId = row.getUUID("column_id");
                        if (columnId != null) {
                            boardColumns.add(new BoardColumn(
                                    columnId, row.getString("column_name"), row.getInteger("column_ordinal")));
                        }
                    }
                    List<Board> out = new ArrayList<>(names.size());
                    names.forEach((id, name) -> out.add(new Board(id, workspaceId, name, columns.get(id))));
                    return List.copyOf(out);
                });
    }

    public Uni<List<UUID>> listBoardIds(UUID workspaceId) {
        return pool.preparedQuery("select id from board where workspace_id = $1 order by id")
                .execute(Tuple.of(workspaceId))
                .map(rows -> {
                    List<UUID> ids = new ArrayList<>(rows.size());
                    rows.forEach(row -> ids.add(row.getUUID("id")));
                    return List.copyOf(ids);
                });
    }
}
