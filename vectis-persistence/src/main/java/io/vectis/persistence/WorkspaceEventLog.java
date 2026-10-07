package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The write path every state change of a workspace goes through (the real-time transport
 * decision, {@code docs/spikes/realtime-transport.md}, Rules → write path).
 *
 * <p>{@link #write} runs the change and its events in one transaction, in this order:
 *
 * <ol>
 *   <li>lock the workspace row ({@code for no key update}) and read the stream's head, its
 *       epoch and the transaction time — before any other row is written, so two writers of
 *       one workspace queue on that row and cannot deadlock on the rows behind it;
 *   <li>apply the change, which raises {@code item.version} by one on every item it writes
 *       and returns the events describing it;
 *   <li>in one statement: append the events at the next {@code seq}s, move the head, and ring
 *       the doorbell, {@code pg_notify('vectis_workspace', '<workspaceId>:<seq>')}.
 * </ol>
 *
 * <p>The {@code seq} is taken in the last statement, so the lock is held for three round
 * trips and the commit. Because it is held until commit, a workspace's {@code seq}s are
 * gap-free and commit in {@code seq} order. PostgreSQL delivers a notification only if its
 * transaction commits, and the event row commits with the change or not at all: there is no
 * event without its change and no change without its event.
 *
 * <p>A change that produces several events (a sprint completion moving several items) gets
 * consecutive {@code seq}s and rings once, with the last: the doorbell says "read the log up
 * to here", not "this one event". A change that produces none consumes no {@code seq} and
 * rings nothing.
 *
 * <p>A write made any other way is invisible to every board. Every mutating repository method
 * goes through here.
 */
@ApplicationScoped
public class WorkspaceEventLog {

    /** The doorbell channel; the payload is {@code <workspaceId>:<seq>}, about 40 bytes. */
    public static final String CHANNEL = "vectis_workspace";

    /** The {@code Vectis-Origin} tag a tab sends: opaque, not an identity. */
    private static final Pattern ORIGIN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** UTC with microseconds, PostgreSQL's precision, always six digits. */
    private static final DateTimeFormatter AT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final Pool pool;

    public WorkspaceEventLog(Pool pool) {
        this.pool = pool;
    }

    /**
     * An event before it has a position: its {@code type} and the members that follow the
     * envelope ({@code item} and {@code changed} for an item event, {@code reason} for
     * {@code resync}), in wire order.
     */
    public record Event(String type, JsonObject body) {
        public Event {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(body, "body");
        }
    }

    /** What a change hands back: its result, and the events that describe it, in order. */
    public record Change<T>(T result, List<Event> events) {
        public Change {
            events = List.copyOf(events);
        }

        public static <T> Change<T> of(T result, Event... events) {
            return new Change<>(result, List.of(events));
        }
    }

    /**
     * What a change runs on: the transaction's connection, already holding the workspace row
     * lock, and the transaction's time ({@code now()}), which is the event's {@code at} and the
     * {@code updated_at} the change writes.
     */
    public record Scope(SqlConnection connection, OffsetDateTime at) {}

    private record Head(StreamCursor cursor, OffsetDateTime at) {}

    /**
     * Runs {@code change} and appends its events, in one transaction.
     *
     * @param origin the writer's {@code Vectis-Origin} tag; anything that is not
     *     {@code [A-Za-z0-9._-]{1,64}} is recorded as {@code null}
     * @throws WorkspaceNotFoundException (as the failure) if the workspace does not exist
     */
    public <T> Uni<T> write(UUID workspaceId, String origin, Function<Scope, Uni<Change<T>>> change) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        String tag = normalizeOrigin(origin);
        return pool.withTransaction(conn -> lock(conn, workspaceId)
                .chain(head -> change.apply(new Scope(conn, head.at()))
                        .chain(done -> append(conn, workspaceId, tag, head, done.events())
                                .replaceWith(done.result()))));
    }

    private static Uni<Head> lock(SqlConnection conn, UUID workspaceId) {
        return conn.preparedQuery("""
                        select event_seq, stream_epoch, now() as at
                          from workspace
                         where id = $1
                           for no key update
                        """)
                .execute(Tuple.of(workspaceId))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        throw new WorkspaceNotFoundException(workspaceId);
                    }
                    var row = rows.iterator().next();
                    return new Head(
                            new StreamCursor(row.getUUID("stream_epoch"), row.getLong("event_seq")),
                            row.getOffsetDateTime("at"));
                });
    }

    private static Uni<Void> append(
            SqlConnection conn, UUID workspaceId, String origin, Head head, List<Event> events) {
        if (events.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        int n = events.size();
        Long[] seqs = new Long[n];
        String[] types = new String[n];
        String[] payloads = new String[n];
        for (int i = 0; i < n; i++) {
            Event event = events.get(i);
            seqs[i] = head.cursor().seq() + 1 + i;
            types[i] = event.type();
            payloads[i] = envelope(event, workspaceId, seqs[i], head.at(), origin).encode();
        }
        long expectedHead = seqs[n - 1];
        // Data-modifying CTEs run exactly once whether or not the outer query reads them.
        return conn.preparedQuery("""
                        with appended as (
                            insert into workspace_event (workspace_id, seq, type, payload)
                            select $1, e.seq, e.type, e.payload
                              from unnest($2::bigint[], $3::text[], $4::text[]) as e (seq, type, payload)
                        ), head as (
                            update workspace set event_seq = event_seq + $5 where id = $1
                            returning event_seq
                        )
                        select event_seq, pg_notify('%s', $1::text || ':' || event_seq)::text as rung
                          from head
                        """.formatted(CHANNEL))
                .execute(Tuple.of(workspaceId, seqs, types, payloads, (long) n))
                .invoke(rows -> {
                    long moved = rows.iterator().next().getLong("event_seq");
                    if (moved != expectedHead) { // unreachable while the lock is held; fail loudly if not
                        throw new IllegalStateException("workspace " + workspaceId + " head moved to "
                                + moved + " under its own lock, expected " + expectedHead);
                    }
                })
                .replaceWithVoid();
    }

    /** The envelope, keys in wire order: {@code type, workspaceId, seq, at, origin}, then the body. */
    private static JsonObject envelope(Event event, UUID workspaceId, long seq, OffsetDateTime at, String origin) {
        JsonObject json = new JsonObject()
                .put("type", event.type())
                .put("workspaceId", workspaceId.toString())
                .put("seq", seq)
                .put("at", formatAt(at))
                .put("origin", origin);
        event.body().forEach(member -> json.put(member.getKey(), member.getValue()));
        return json;
    }

    static String formatAt(OffsetDateTime at) {
        return AT.format(at);
    }

    static String normalizeOrigin(String origin) {
        return origin != null && ORIGIN.matcher(origin).matches() ? origin : null;
    }
}
