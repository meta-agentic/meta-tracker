package io.vectis.spike.realtime;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import java.util.UUID;

/**
 * Demonstration data: three workspaces, two on the {@code scrum} template and one on
 * {@code kanban}, one board, and two items in {@code VEC}. Column ids stand in for VEC-45's
 * projected {@code board_column} rows.
 */
final class Seed {

    private Seed() {}

    static Uni<String> reset(Pool pool) {
        UUID vec = Ids.next();
        UUID ops = Ids.next();
        UUID web = Ids.next();
        UUID board = Ids.next();
        UUID todo = Ids.next();
        UUID inProgress = Ids.next();
        UUID done = Ids.next();
        return pool.withTransaction(conn -> conn.query("truncate workspace cascade").execute()
                        .flatMap(x -> conn.preparedQuery("""
                                        insert into workspace (id, key, name, template_key, item_counter) values
                                            ($1, 'VEC', 'Vectis', 'scrum', 2),
                                            ($2, 'OPS', 'Operations', 'scrum', 0),
                                            ($3, 'WEB', 'Website', 'kanban', 0)
                                        """)
                                .execute(Tuple.of(vec, ops, web)))
                        .flatMap(x -> conn.preparedQuery("""
                                        insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields) values
                                            ($1, $3, $4, $5, 'VEC-1', 'Stream workspace events over SSE', 'h', '{"type": "story", "storyPoints": 5}'),
                                            ($2, $3, $4, $5, 'VEC-2', 'Highlight remote updates', 'p', '{"type": "story", "storyPoints": 3}')
                                        """)
                                .execute(Tuple.of(Ids.next(), Ids.next(), vec, board, todo))))
                .replaceWith(new JsonObject()
                        .put("workspaces", new JsonObject().put("VEC", vec.toString()).put("OPS", ops.toString())
                                .put("WEB", web.toString()))
                        .put("boardId", board.toString())
                        .put("columns", new JsonObject().put("todo", todo.toString())
                                .put("in-progress", inProgress.toString()).put("done", done.toString()))
                        .encode());
    }
}
