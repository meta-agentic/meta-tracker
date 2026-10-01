package io.vectis.spike.realtime;

import io.quarkus.logging.Log;
import io.vertx.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * This instance's open SSE streams, grouped by workspace, and the per-workspace feed that
 * moves events from the {@code workspace_event} log onto them.
 *
 * <p>Ordering argument. A workspace's {@code seq} is allocated under its row lock, so a
 * transaction holding seq n+1 cannot exist until the one holding n has committed:
 * sequences are gap-free and commit in order. A feed therefore reads {@code seq > head}
 * and never skips one. A new stream registers before it reads its replay, queues whatever
 * the feed delivers meanwhile, and drops anything at or below the last seq it sent, so
 * replay and live join without a gap or a duplicate.
 *
 * <p>Every mutation of hub state is under the hub's monitor; database work is not.
 */
@ApplicationScoped
public class Hub {

    record Frame(long seq, String type, String json) {}

    static final class Stream {
        final UUID workspaceId;
        final SseEventSink sink;
        final Sse sse;
        long lastSent;
        boolean ready;
        final List<Frame> queued = new ArrayList<>();

        Stream(UUID workspaceId, SseEventSink sink, Sse sse) {
            this.workspaceId = workspaceId;
            this.sink = sink;
            this.sse = sse;
        }
    }

    private static final class Feed {
        final UUID workspaceId;
        Long head;
        boolean fetching;
        boolean pending;
        final Set<Stream> streams = new LinkedHashSet<>();

        Feed(UUID workspaceId) {
            this.workspaceId = workspaceId;
        }
    }

    /** Replay is bounded; a client further behind than this is told to resync. */
    static final int MAX_REPLAY = 10_000;
    private static final int FETCH_BATCH = 500;

    private final Pool pool;
    private final Vertx vertx;
    private final long feedDelayMs;
    private final Map<UUID, Feed> feeds = new HashMap<>();

    /**
     * {@code spike.feed-delay-ms} stalls this instance's feed before it delivers what it
     * read, standing in for a GC pause or a slow read. Demonstration only; default 0.
     */
    public Hub(Pool pool, Vertx vertx, @ConfigProperty(name = "spike.feed-delay-ms", defaultValue = "0") long feedDelayMs) {
        this.pool = pool;
        this.vertx = vertx;
        this.feedDelayMs = feedDelayMs;
    }

    // ---- subscribe: register, replay from the cursor, go live --------------------------

    public void subscribe(UUID workspaceId, String workspaceKey, Long after, SseEventSink sink, Sse sse) {
        Stream stream = new Stream(workspaceId, sink, sse);
        synchronized (this) {
            feeds.computeIfAbsent(workspaceId, Feed::new).streams.add(stream);
        }
        // The browser's EventSource retries after `retry` ms. Each stream gets its own value
        // so the clients of a pod that dies do not all reconnect in the same instant.
        long retry = 1000 + ThreadLocalRandom.current().nextLong(2000);
        send(stream, sse.newEventBuilder()
                .comment("workspace " + workspaceKey + (after == null ? "" : " after " + after))
                .reconnectDelay(retry)
                .build());

        // One statement is one snapshot: the head and the rows agree.
        pool.preparedQuery("""
                        select w.event_seq as head,
                               (select min(seq) from workspace_event where workspace_id = w.id) as oldest,
                               e.seq, e.type, e.payload
                          from workspace w
                          left join workspace_event e
                                 on e.workspace_id = w.id and e.seq > $2 and e.seq <= w.event_seq
                         where w.id = $1
                         order by e.seq
                         limit $3
                        """)
                .execute(Tuple.of(workspaceId, after == null ? Long.MAX_VALUE : after, MAX_REPLAY + 1))
                .subscribe().with(rows -> replayed(stream, after, rows), failure -> {
                    Log.errorf(failure, "replay failed for %s", workspaceId);
                    close(stream);
                });
    }

    private void replayed(Stream stream, Long after, RowSet<Row> rows) {
        Row first = rows.iterator().next();
        long head = first.getLong("head");
        Long oldest = first.getLong("oldest");
        List<Frame> replay = new ArrayList<>();
        for (Row row : rows) {
            if (row.getLong("seq") != null) {
                replay.add(new Frame(row.getLong("seq"), row.getString("type"), row.getString("payload")));
            }
        }
        boolean gap = after != null
                && (after > head
                        || (oldest != null && after < oldest - 1)
                        || (oldest == null && after < head)
                        || replay.size() > MAX_REPLAY);
        synchronized (this) {
            if (gap) {
                String at = Events.at(OffsetDateTime.now());
                Frame resync = new Frame(head, "resync",
                        Events.resync(stream.workspaceId, head, at, after > head ? "unknown-cursor" : "retention"));
                sendFrame(stream, resync);
                stream.lastSent = head;
            } else {
                stream.lastSent = after == null ? head : after;
                for (Frame frame : replay) {
                    if (frame.seq() > stream.lastSent) {
                        sendFrame(stream, frame);
                        stream.lastSent = frame.seq();
                    }
                }
            }
            for (Frame frame : stream.queued) {
                if (frame.seq() > stream.lastSent) {
                    sendFrame(stream, frame);
                    stream.lastSent = frame.seq();
                }
            }
            stream.queued.clear();
            stream.ready = true;
            Feed feed = feeds.get(stream.workspaceId);
            if (feed != null && feed.head == null) {
                feed.head = head;
                if (feed.pending) {
                    feed.pending = false;
                    startFetch(feed);
                }
            }
        }
    }

    // ---- carrier = pg: the doorbell ----------------------------------------------------

    /** A NOTIFY arrived: workspace {@code workspaceId} has committed {@code seq}. */
    public synchronized void onDoorbell(UUID workspaceId, long seq) {
        Feed feed = feeds.get(workspaceId);
        if (feed == null) {
            return; // nobody on this instance watches that workspace
        }
        if (feed.head == null || feed.fetching) {
            feed.pending = true;
            return;
        }
        if (seq > feed.head) {
            startFetch(feed);
        }
    }

    /**
     * The LISTEN connection was (re)established. Notifications sent while it was down are
     * gone, so every feed reads the log from its head: lost doorbells cost latency, never
     * events.
     */
    public synchronized void onListening() {
        for (Feed feed : feeds.values()) {
            if (feed.head == null || feed.fetching) {
                feed.pending = true;
            } else {
                startFetch(feed);
            }
        }
    }

    private void startFetch(Feed feed) {
        feed.fetching = true;
        long from = feed.head;
        pool.preparedQuery("""
                        select seq, type, payload from workspace_event
                         where workspace_id = $1 and seq > $2
                         order by seq limit $3
                        """)
                .execute(Tuple.of(feed.workspaceId, from, FETCH_BATCH))
                .subscribe().with(rows -> {
                    if (feedDelayMs > 0) {
                        vertx.setTimer(feedDelayMs, id -> fetched(feed, rows));
                    } else {
                        fetched(feed, rows);
                    }
                }, failure -> {
                    Log.errorf(failure, "fetch failed for %s", feed.workspaceId);
                    synchronized (this) {
                        feed.fetching = false;
                        feed.pending = true;
                    }
                });
    }

    private synchronized void fetched(Feed feed, RowSet<Row> rows) {
        for (Row row : rows) {
            Frame frame = new Frame(row.getLong("seq"), row.getString("type"), row.getString("payload"));
            for (Stream stream : List.copyOf(feed.streams)) {
                offerOrdered(stream, frame);
            }
            feed.head = frame.seq();
        }
        feed.fetching = false;
        if (feed.streams.isEmpty()) {
            feeds.remove(feed.workspaceId, feed);
            return;
        }
        if (feed.pending || rows.size() == FETCH_BATCH) {
            feed.pending = false;
            startFetch(feed);
        }
    }

    private void offerOrdered(Stream stream, Frame frame) {
        if (!stream.ready) {
            stream.queued.add(frame);
        } else if (frame.seq() > stream.lastSent) {
            sendFrame(stream, frame);
            stream.lastSent = frame.seq();
        }
    }

    // ---- carrier = none / pg-postcommit: deliver as it comes -------------------------

    /**
     * Delivers to this instance's streams exactly as the event arrived: no log, no order
     * check. That is what an after-commit broadcast or broker consumer does, and it is
     * the behaviour the spike demonstrates the hazards of.
     */
    public synchronized void deliverAsArrived(UUID workspaceId, long seq, String type, String json) {
        Feed feed = feeds.get(workspaceId);
        if (feed == null) {
            return;
        }
        Frame frame = new Frame(seq, type, json);
        for (Stream stream : List.copyOf(feed.streams)) {
            if (!stream.ready) {
                stream.queued.add(frame);
            } else {
                sendFrame(stream, frame);
                stream.lastSent = Math.max(stream.lastSent, seq);
            }
        }
    }

    // ---- plumbing ---------------------------------------------------------------------

    /** Sends a comment to every stream; finds closed ones. Called on a timer. */
    public synchronized void keepAlive() {
        for (Feed feed : List.copyOf(feeds.values())) {
            for (Stream stream : List.copyOf(feed.streams)) {
                send(stream, stream.sse.newEventBuilder().comment("keep-alive").build());
            }
        }
    }

    public synchronized int streamCount() {
        return feeds.values().stream().mapToInt(f -> f.streams.size()).sum();
    }

    private void sendFrame(Stream stream, Frame frame) {
        send(stream, stream.sse.newEventBuilder()
                .id(Long.toString(frame.seq()))
                .name(frame.type())
                .data(String.class, frame.json())
                .build());
    }

    private void send(Stream stream, OutboundSseEvent event) {
        if (stream.sink.isClosed()) {
            close(stream);
            return;
        }
        stream.sink.send(event).whenComplete((ok, failure) -> {
            if (failure != null) {
                close(stream);
            }
        });
    }

    private synchronized void close(Stream stream) {
        Feed feed = feeds.get(stream.workspaceId);
        if (feed != null) {
            feed.streams.remove(stream);
            if (feed.streams.isEmpty() && !feed.fetching) {
                feeds.remove(stream.workspaceId);
            }
        }
        try {
            stream.sink.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }
}
