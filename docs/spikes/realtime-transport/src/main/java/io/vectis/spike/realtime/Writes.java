package io.vectis.spike.realtime;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The write path every state change goes through, in one transaction:
 *
 * <ol>
 *   <li>lock the workspace row and take the next {@code seq}
 *       ({@code update workspace set event_seq = event_seq + 1 ... returning});
 *   <li>apply the change, bumping {@code item.version};
 *   <li>append the event to {@code workspace_event};
 *   <li>ring the doorbell ({@code pg_notify}, carrier {@code pg} only).
 * </ol>
 *
 * The workspace row is always locked first, so writers cannot deadlock on each other.
 * VEC-45's configuration write already takes this lock, so the seq costs no new
 * contention there; for item writes it serialises writers within one workspace.
 */
@ApplicationScoped
public class Writes {

    record Locked(SqlConnection conn, UUID workspaceId, String workspaceKey, long seq, long itemNumber) {}

    record Built(String type, String json, JsonObject body) {}

    record Written(UUID workspaceId, long seq, Built built) {}

    private final Pool pool;
    private final Carrier carrier;

    public Writes(Pool pool, Carrier carrier) {
        this.pool = pool;
        this.carrier = carrier;
    }

    Uni<Written> write(String workspaceKey, boolean newItem, Function<Locked, Uni<Built>> change) {
        return pool.withTransaction(conn -> conn.preparedQuery("""
                                update workspace
                                   set event_seq = event_seq + 1,
                                       item_counter = item_counter + $2
                                 where key = $1
                             returning id, key, event_seq, item_counter
                                """)
                        .execute(Tuple.of(workspaceKey, newItem ? 1 : 0))
                        .flatMap(rows -> {
                            if (rows.size() == 0) {
                                return Uni.createFrom().failure(new NotFoundException("no workspace " + workspaceKey));
                            }
                            Row ws = rows.iterator().next();
                            Locked locked = new Locked(conn, ws.getUUID("id"), ws.getString("key"),
                                    ws.getLong("event_seq"), ws.getLong("item_counter"));
                            return change.apply(locked).flatMap(built -> append(conn, locked.workspaceId(), locked.seq(), built));
                        }))
                .call(w -> carrier.afterCommit(w.workspaceId(), w.seq(), w.built().type(), w.built().json()));
    }

    private Uni<Written> append(SqlConnection conn, UUID workspaceId, long seq, Built built) {
        return conn.preparedQuery("insert into workspace_event (workspace_id, seq, type, payload) values ($1, $2, $3, $4)")
                .execute(Tuple.of(workspaceId, seq, built.type(), built.json()))
                .flatMap(ignored -> carrier.inTransaction(conn, workspaceId, seq))
                .replaceWith(new Written(workspaceId, seq, built));
    }

    /**
     * An ancestor template level published a change its descendants inherit: every
     * affected workspace gets its own revision bump and its own {@code configuration.changed}
     * event with its own seq, all in the publishing transaction. Workspaces are locked in id
     * order. Whether a publish changes descendants at once or at their next upgrade is
     * VEC-66's decision; the event fires in whichever transaction changes a workspace's
     * effective configuration.
     */
    Uni<List<Written>> publishAncestor(String templateKey, int version, String origin) {
        JsonObject cause = new JsonObject().put("kind", "ancestor").put("template", templateKey).put("version", version);
        List<Written> out = new ArrayList<>();
        return pool.withTransaction(conn -> conn.preparedQuery("""
                                select id from workspace where template_key = $1 order by id for update
                                """)
                        .execute(Tuple.of(templateKey))
                        .flatMap(rows -> {
                            Uni<Void> chain = Uni.createFrom().voidItem();
                            for (Row row : rows) {
                                UUID id = row.getUUID("id");
                                chain = chain.chain(() -> bumpRevision(conn, id, origin, cause).invoke(out::add).replaceWithVoid());
                            }
                            return chain;
                        }))
                .flatMap(v -> {
                    Uni<Void> after = Uni.createFrom().voidItem();
                    for (Written w : out) {
                        after = after.chain(() -> carrier.afterCommit(w.workspaceId(), w.seq(), w.built().type(), w.built().json()));
                    }
                    return after;
                })
                .replaceWith(out);
    }

    Uni<Written> changeConfiguration(String workspaceKey, String origin, JsonObject cause) {
        return pool.withTransaction(conn -> conn.preparedQuery("select id from workspace where key = $1 for update")
                        .execute(Tuple.of(workspaceKey))
                        .flatMap(rows -> rows.size() == 0
                                ? Uni.createFrom().failure(new NotFoundException("no workspace " + workspaceKey))
                                : bumpRevision(conn, rows.iterator().next().getUUID("id"), origin, cause)))
                .call(w -> carrier.afterCommit(w.workspaceId(), w.seq(), w.built().type(), w.built().json()));
    }

    private Uni<Written> bumpRevision(SqlConnection conn, UUID workspaceId, String origin, JsonObject cause) {
        return conn.preparedQuery("""
                        update workspace
                           set event_seq = event_seq + 1, config_revision = config_revision + 1
                         where id = $1
                     returning event_seq, config_revision, now() as at
                        """)
                .execute(Tuple.of(workspaceId))
                .flatMap(rows -> {
                    Row row = rows.iterator().next();
                    long seq = row.getLong("event_seq");
                    long revision = row.getLong("config_revision");
                    String json = Events.configurationChanged(workspaceId, seq, Events.at(row.getOffsetDateTime("at")),
                            origin, revision, cause);
                    JsonObject body = new JsonObject().put("workspaceId", workspaceId.toString()).put("revision", revision);
                    return append(conn, workspaceId, seq, new Built("configuration.changed", json, body));
                });
    }
}
