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
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The write path every item change goes through (the real-time transport decision,
 * {@code docs/spikes/realtime-transport.md}, Rules → write path).
 *
 * <p>A write runs the change and its events in one transaction, in this order:
 *
 * <ol>
 *   <li>lock the workspace row ({@code for no key update}) and read the stream's head, its
 *       epoch and the transaction time — before any other row is written, so two writers of
 *       one workspace queue on that row and cannot deadlock on the rows behind it. The
 *       workspace is resolved in that same statement from what the write targets (a board, a
 *       stored item, or the workspace itself), never taken from the caller's copy of a row;
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
 * <p>An item change made any other way is invisible to every board; every item mutation in
 * {@link ItemRepository} and the item moves of {@link SprintRepository#complete} go through
 * here. Writes that change no item append nothing today: sprint creation and start, a
 * sprint completion that moves no item, board and workspace creation. The event contract has
 * no sprint or board events yet.
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
     * lock; the workspace that lock resolved, which is the stream the events go to; and the
     * transaction's time ({@code now()}), which is the event's {@code at} and the
     * {@code updated_at} the change writes.
     */
    public record Scope(SqlConnection connection, UUID workspaceId, OffsetDateTime at) {}

    private record Head(UUID workspaceId, StreamCursor cursor, OffsetDateTime at) {}

    private static final String HEAD_COLUMNS = "w.id, w.event_seq, w.stream_epoch, now() as at";

    /**
     * Runs {@code change} on the named workspace and appends its events, in one transaction.
     * Fails with {@link WorkspaceNotFoundException} if the workspace does not exist.
     *
     * @param origin the writer's {@code Vectis-Origin} tag; anything that is not
     *     {@code [A-Za-z0-9._-]{1,64}} is recorded as {@code null}
     */
    public <T> Uni<T> writeToWorkspace(UUID workspaceId, String origin, Function<Scope, Uni<Change<T>>> change) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return write("select " + HEAD_COLUMNS + " from workspace w where w.id = $1 for no key update",
                workspaceId, () -> new WorkspaceNotFoundException(workspaceId), origin, change);
    }

    /**
     * As {@link #writeToWorkspace}, on the workspace that owns {@code boardId}. Fails with
     * {@link PlacementException} if the board does not exist.
     */
    public <T> Uni<T> writeToBoard(UUID boardId, String origin, Function<Scope, Uni<Change<T>>> change) {
        Objects.requireNonNull(boardId, "boardId");
        return write("select " + HEAD_COLUMNS + """
                         from board b join workspace w on w.id = b.workspace_id
                        where b.id = $1
                          for no key update of w
                        """,
                boardId, () -> new PlacementException("board " + boardId + " does not exist"), origin, change);
    }

    /**
     * As {@link #writeToWorkspace}, on the workspace that holds the stored item {@code itemId}.
     * Only the workspace row is locked here; the change locks the item when it writes it.
     * Fails with {@link ItemNotFoundException} if the item does not exist.
     */
    public <T> Uni<T> writeToItem(UUID itemId, String origin, Function<Scope, Uni<Change<T>>> change) {
        Objects.requireNonNull(itemId, "itemId");
        return write("select " + HEAD_COLUMNS + """
                         from item i join workspace w on w.id = i.workspace_id
                        where i.id = $1
                          for no key update of w
                        """,
                itemId, () -> new ItemNotFoundException(itemId), origin, change);
    }

    private <T> Uni<T> write(String lockSql, UUID key, Supplier<RuntimeException> missing, String origin,
            Function<Scope, Uni<Change<T>>> change) {
        String tag = normalizeOrigin(origin);
        return pool.withTransaction(conn -> conn.preparedQuery(lockSql).execute(Tuple.of(key))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        throw missing.get();
                    }
                    var row = rows.iterator().next();
                    return new Head(row.getUUID("id"),
                            new StreamCursor(row.getUUID("stream_epoch"), row.getLong("event_seq")),
                            row.getOffsetDateTime("at"));
                })
                .chain(head -> change.apply(new Scope(conn, head.workspaceId(), head.at()))
                        .chain(done -> append(conn, head.workspaceId(), tag, head, done.events())
                                .replaceWith(done.result()))));
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
