package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.Sprint;
import io.vectis.domain.Workspace;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgConnection;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Parallel writers to one workspace. The write path's ordering argument is that the workspace
 * row lock is taken before any other row and held until commit, so seqs are gap-free, commit
 * in seq order, and writers queue rather than deadlock. These tests put that under contention:
 * many writers, overlapping items, mixed operations, in random order.
 *
 * <p>Commit order is observed, not assumed: PostgreSQL delivers notifications in commit order,
 * so the doorbells a {@code LISTEN} session receives are the commit order of the writes.
 */
@QuarkusTest
@Timeout(120) // a deadlock or a lost wake-up fails the test instead of hanging the build
class EventConcurrencyTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();
    private static final int ITEMS = 8;
    private static final int WRITES = 240;

    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;
    @Inject SprintRepository sprints;
    @Inject Pool pool;

    private record Fixture(Workspace ws, Board board, Sprint sprint, List<Item> items) {}

    private Fixture fixture() {
        String suffix = Integer.toString(KEY_SEQ.incrementAndGet());
        Workspace ws = workspaces.insert(Workspace.create("EC" + suffix, "Workspace " + suffix)).await().indefinitely();
        Board board = boards.insert(Board.create(ws.id(), "Delivery", List.of(
                        BoardColumn.create("Backlog", 0),
                        BoardColumn.create("In Progress", 1),
                        BoardColumn.create("Done", 2))))
                .await().indefinitely();
        Sprint sprint = sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely();
        List<Item> created = new ArrayList<>();
        for (int i = 0; i < ITEMS; i++) {
            created.add(items.insert(Item.create(ws.id(), board.id(), board.orderedColumns().getFirst().id(),
                    ws.key() + "-" + i, "item " + i, "m")).await().indefinitely());
        }
        return new Fixture(ws, board, sprint, created);
    }

    /** One random single-item write: a move, an edit or a sprint assignment. */
    private Uni<Item> randomWrite(Fixture f, Random random, int n) {
        Item item = f.items().get(random.nextInt(ITEMS)); // a stale copy on purpose: the database decides the version
        List<BoardColumn> cols = f.board().orderedColumns();
        return switch (random.nextInt(3)) {
            case 0 -> items.move(item, cols.get(random.nextInt(cols.size())).id(), "r" + n, "w" + n);
            // An edit names the version it read, so a concurrent write makes it conflict: re-read and retry.
            case 1 -> Uni.createFrom().deferred(() -> items.findById(item.id())
                            .chain(current -> items.edit(current.orElseThrow().withFields(Map.of("n", n)),
                                    current.orElseThrow().version(), "w" + n)))
                    .onFailure(ItemVersionConflictException.class).retry().atMost(1_000);
            default -> items.moveToSprint(item, random.nextBoolean() ? f.sprint().id() : null, "w" + n);
        };
    }

    private List<JsonObject> payloads(Workspace ws) {
        List<JsonObject> out = new ArrayList<>();
        pool.preparedQuery("select payload from workspace_event where workspace_id = $1 order by seq")
                .execute(Tuple.of(ws.id())).await().indefinitely()
                .forEach(row -> out.add(new JsonObject(row.getString("payload"))));
        return out;
    }

    @Test
    void parallelWritersGetGapFreeCommitOrderedSeqsAndNeverDeadlock() throws Exception {
        Fixture f = fixture();
        List<Long> doorbells = new CopyOnWriteArrayList<>();
        SqlConnection listener = pool.getConnection().await().indefinitely();
        try {
            PgConnection pg = PgConnection.cast(listener);
            pg.notificationHandler(n -> {
                if (WorkspaceEventLog.CHANNEL.equals(n.getChannel())
                        && n.getPayload().startsWith(f.ws().id() + ":")) {
                    doorbells.add(Long.parseLong(n.getPayload().substring(n.getPayload().indexOf(':') + 1)));
                }
            });
            pg.query("listen " + WorkspaceEventLog.CHANNEL).execute().await().indefinitely();

            Random random = new Random(73);
            List<CompletableFuture<Item>> inFlight = new ArrayList<>();
            for (int n = 0; n < WRITES; n++) {
                inFlight.add(randomWrite(f, random, n).subscribeAsCompletionStage());
            }
            List<Throwable> failures = new ArrayList<>();
            for (CompletableFuture<Item> write : inFlight) {
                try {
                    write.join();
                } catch (RuntimeException e) {
                    failures.add(e);
                }
            }
            assertEquals(List.of(), failures, "every writer commits: none deadlocks, none is refused");

            long expectedHead = ITEMS + WRITES;
            List<JsonObject> log = payloads(f.ws());
            assertEquals(LongStream.rangeClosed(1, expectedHead).boxed().toList(),
                    log.stream().map(p -> p.getLong("seq")).toList(), "gap-free seqs from 1");

            // Per item, the versions on the stream climb by exactly one, and end at the stored version.
            Map<String, Long> lastVersion = new HashMap<>();
            for (JsonObject p : log) {
                JsonObject item = p.getJsonObject("item");
                long version = item.getLong("version");
                Long previous = lastVersion.put(item.getString("id"), version);
                assertEquals(previous == null ? 1 : previous + 1, version,
                        "versions of one item are consecutive in seq order: " + p.encode());
            }
            for (Item item : f.items()) {
                assertEquals(lastVersion.get(item.id().toString()),
                        items.findById(item.id()).await().indefinitely().orElseThrow().version());
            }

            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while ((doorbells.isEmpty() || doorbells.getLast() < expectedHead) && Instant.now().isBefore(deadline)) {
                Thread.sleep(20);
            }
            assertEquals(LongStream.rangeClosed(ITEMS + 1, expectedHead).boxed().toList(), doorbells,
                    "one doorbell per write, in commit order, which is seq order");
        } finally {
            listener.query("unlisten *").execute().await().indefinitely();
            listener.close().await().indefinitely();
        }
    }

    /**
     * A snapshot read while writers run must be internally consistent: every write here raises
     * one item's version by one and appends one event, so a cursor at seq S over items created
     * at seqs 1..ITEMS must see exactly {@code S - ITEMS} version increments.
     */
    @Test
    void snapshotsTakenDuringWritesAgreeWithTheirCursor() {
        Fixture f = fixture();
        // Eight lanes of sequential writers rather than all at once: the pool must keep a
        // connection free for the snapshots, or they would queue behind every write.
        int lanes = 8;
        List<CompletableFuture<?>> inFlight = new ArrayList<>();
        for (int lane = 0; lane < lanes; lane++) {
            Random random = new Random(74 + lane);
            CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
            for (int k = 0; k < WRITES / lanes; k++) {
                int n = lane * 1000 + k;
                chain = chain.thenCompose(ignored -> randomWrite(f, random, n).subscribeAsCompletionStage());
            }
            inFlight.add(chain);
        }

        AtomicBoolean writing = new AtomicBoolean(true);
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> writing.set(false));
        int snapshots = 0;
        do {
            WorkspaceSnapshot snapshot = items.snapshot(f.ws().id()).await().indefinitely().orElseThrow();
            long increments = snapshot.items().stream().mapToLong(i -> i.version() - 1).sum();
            assertEquals(snapshot.cursor().seq() - ITEMS, increments, "snapshot at " + snapshot.cursor());
            snapshots++;
        } while (writing.get());

        inFlight.forEach(CompletableFuture::join);
        WorkspaceSnapshot last = items.snapshot(f.ws().id()).await().indefinitely().orElseThrow();
        assertEquals(ITEMS + WRITES, last.cursor().seq());
        assertTrue(snapshots > 1, "snapshots were taken while the writes ran: " + snapshots);
    }

    @Test
    void writersOfDifferentWorkspacesDoNotShareASequence() {
        Fixture a = fixture();
        Fixture b = fixture();
        Random random = new Random(75);
        List<CompletableFuture<Item>> inFlight = new ArrayList<>();
        for (int n = 0; n < 60; n++) {
            inFlight.add(randomWrite(n % 2 == 0 ? a : b, random, n).subscribeAsCompletionStage());
        }
        inFlight.forEach(CompletableFuture::join);

        for (Fixture f : List.of(a, b)) {
            assertEquals(LongStream.rangeClosed(1, ITEMS + 30).boxed().toList(),
                    payloads(f.ws()).stream().map(p -> p.getLong("seq")).toList());
            UUID wsId = f.ws().id();
            assertTrue(payloads(f.ws()).stream().allMatch(p -> p.getString("workspaceId").equals(wsId.toString())));
        }
    }
}
