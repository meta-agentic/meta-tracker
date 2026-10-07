package io.vectis.spike.tenancy;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Sub-question 2: the board read path ({@code ItemRepository.findByColumn}: every item of one
 * column of one board, in rank order) under RLS against application-level filtering.
 *
 * <ul>
 *   <li>{@code APP}: {@code item_plain} with {@code where tenant_id = $n}, one pooled statement;
 *   <li>{@code APP_TX}: the same inside {@code pool.withTransaction}, to separate the cost of the
 *       transaction from the cost of the policy;
 *   <li>{@code RLS}: {@code item} under its policy: a transaction, {@code set_config}, then the
 *       product's query unchanged;
 *   <li>{@code RLS_PIPELINED}: as {@code RLS}, but {@code set_config} and the query are sent
 *       together on the transaction's connection without waiting for the first to answer (the
 *       client pipelines commands on one connection, in order), saving one round trip;
 *   <li>{@code RLS_AND_APP}: both layers: the policy, and the predicate in the query.
 * </ul>
 *
 * <p>Every request picks a random tenant and one of its three columns, and must get rows back.
 * Modes run in rounds, in a shuffled order per round, so drift in the machine is spread over
 * all of them.
 */
final class Bench {

    enum Mode { APP, APP_TX, RLS, RLS_PIPELINED, RLS_AND_APP }

    private static final String COLUMNS =
            "id, workspace_id, board_id, column_id, key, title, rank, fields, sprint_id, version";

    record Target(UUID tenant, UUID board, UUID column) {}

    private Bench() {}

    static void run(Pool pool, List<Target> targets, int perModePerRound, int rounds, int inFlight, long seed) {
        Random random = new Random(seed);
        Map<Mode, List<Long>> samples = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            samples.put(mode, new ArrayList<>());
            measure(pool, mode, targets, 1_000, inFlight, random); // warm-up, discarded
        }
        for (int round = 0; round < rounds; round++) {
            List<Mode> order = new ArrayList<>(List.of(Mode.values()));
            java.util.Collections.shuffle(order, random);
            for (Mode mode : order) {
                samples.get(mode).addAll(measure(pool, mode, targets, perModePerRound, inFlight, random));
            }
        }
        Log.out("bench: in-flight=%d, %d rounds x %d requests per mode, latency per request in ms",
                inFlight, rounds, perModePerRound);
        Log.out("bench: %-12s %8s %8s %8s %8s %8s", "mode", "n", "p50", "p95", "p99", "mean");
        for (Mode mode : Mode.values()) {
            long[] ns = samples.get(mode).stream().mapToLong(Long::longValue).sorted().toArray();
            Log.out("bench: %-12s %8d %8.3f %8.3f %8.3f %8.3f", mode, ns.length, ms(pct(ns, 50)), ms(pct(ns, 95)),
                    ms(pct(ns, 99)), ms((long) Arrays.stream(ns).average().orElse(0)));
        }
    }

    private static List<Long> measure(Pool pool, Mode mode, List<Target> targets, int n, int inFlight, Random random) {
        List<Target> picks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            picks.add(targets.get(random.nextInt(targets.size())));
        }
        return Multi.createFrom().iterable(picks)
                .onItem().transformToUni(t -> timed(read(pool, mode, t)))
                .merge(inFlight)
                .collect().asList()
                .await().indefinitely();
    }

    private static Uni<Long> timed(Uni<Integer> read) {
        return Uni.createFrom().deferred(() -> {
            long start = System.nanoTime();
            return read.map(rows -> {
                if (rows == 0) {
                    throw new IllegalStateException("a board read returned no rows");
                }
                return System.nanoTime() - start;
            });
        });
    }

    private static Uni<Integer> read(Pool pool, Mode mode, Target t) {
        return switch (mode) {
            case APP -> appRead(pool, t);
            case APP_TX -> pool.withTransaction(c -> appRead(c, t));
            case RLS -> pool.withTransaction(c -> bind(c, t).chain(() -> c.preparedQuery(
                            "select " + COLUMNS + " from item where board_id = $1 and column_id = $2 order by rank")
                    .execute(Tuple.of(t.board(), t.column())).map(rows -> rows.size())));
            case RLS_PIPELINED -> pool.withTransaction(c -> Uni.combine().all()
                    .unis(bind(c, t), c.preparedQuery(
                                    "select " + COLUMNS + " from item where board_id = $1 and column_id = $2 order by rank")
                            .execute(Tuple.of(t.board(), t.column())).map(rows -> rows.size()))
                    .with((bound, rows) -> rows));
            case RLS_AND_APP -> pool.withTransaction(c -> bind(c, t).chain(() -> c.preparedQuery(
                            "select " + COLUMNS + " from item where tenant_id = $1 and board_id = $2 and column_id = $3"
                                    + " order by rank")
                    .execute(Tuple.of(t.tenant(), t.board(), t.column())).map(rows -> rows.size())));
        };
    }

    private static Uni<Integer> appRead(SqlClient client, Target t) {
        return client.preparedQuery("select " + COLUMNS
                        + " from item_plain where tenant_id = $1 and board_id = $2 and column_id = $3 order by rank")
                .execute(Tuple.of(t.tenant(), t.board(), t.column()))
                .map(rows -> rows.size());
    }

    private static Uni<Void> bind(SqlClient client, Target t) {
        return client.preparedQuery("select set_config('app.current_tenant_id', $1, true)")
                .execute(Tuple.of(t.tenant().toString()))
                .replaceWithVoid();
    }

    /** The plans of the application-filtered and the RLS read, as the application role sees them. */
    static void explain(Pool pool, Target t) {
        pool.preparedQuery("""
                        explain (analyze, buffers off, timing off, summary off)
                        select %s from item_plain where tenant_id = $1 and board_id = $2 and column_id = $3
                         order by rank""".formatted(COLUMNS))
                .execute(Tuple.of(t.tenant(), t.board(), t.column()))
                .await().indefinitely()
                .forEach(row -> Log.out("plan APP: %s", row.getString(0)));
        pool.withTransaction(c -> bind(c, t).chain(() -> c.preparedQuery("""
                        explain (analyze, buffers off, timing off, summary off)
                        select %s from item where board_id = $1 and column_id = $2 order by rank""".formatted(COLUMNS))
                        .execute(Tuple.of(t.board(), t.column()))))
                .await().indefinitely()
                .forEach(row -> Log.out("plan RLS: %s", row.getString(0)));
    }

    private static long pct(long[] sorted, int p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
    }

    private static double ms(long ns) {
        return ns / 1_000_000.0;
    }
}
