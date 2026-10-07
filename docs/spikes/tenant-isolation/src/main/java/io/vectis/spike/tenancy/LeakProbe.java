package io.vectis.spike.tenancy;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Sub-question 1: can a request served by a pooled connection see the tenant binding of the
 * request that used the connection before it?
 *
 * <p>Requests of several kinds are interleaved at random, for random tenants, with many more
 * in flight than the pool has connections, so every physical connection is handed from one
 * kind and tenant to the next many times. Every request that reads reports the backend pid
 * that served it, the tenant setting it saw, and the rows it could see; a leak is any read
 * that sees a tenant it was not bound to, or any row while bound to none.
 *
 * <p>Two negative controls show the probe detects a leak when one exists:
 *
 * <ul>
 *   <li>{@code SESSION}: the binding is {@code set_config(..., false)}, a plain {@code SET},
 *       which outlives the transaction and stays on the pooled connection;
 *   <li>{@code TEXTUAL}: the binding is wrapped in transaction control sent as SQL text
 *       ({@code begin; ...} on a bare pool statement, or {@code query("begin")} on a borrowed
 *       connection that is then closed), which the pool does not know about: the connection
 *       goes back to the pool still inside the bound transaction.
 * </ul>
 */
final class LeakProbe {

    enum Mode { LOCAL, SESSION, TEXTUAL }

    enum Kind {
        /** set_config(..., true) inside pool.withTransaction, then read: the design under test. */
        BOUND,
        /** a plain pool query, no binding at all: must see nothing. */
        UNBOUND,
        /** pool.withTransaction with no binding: must see nothing. */
        UNBOUND_TX,
        /** binds, then fails inside the transaction: rolled back. */
        FAILING,
        /**
         * binds, writes a marker row, and is given up by its subscriber after 5 ms (a request
         * timeout). The client does not stop the transaction: it runs on and commits.
         */
        TIMED_OUT,
        /** borrows a connection, begin() through the API, binds, and closes it mid-transaction. */
        ABANDONED,
        /** binds with set_config(..., true) as its own pooled statement, then reads in another. */
        BOUND_OUTSIDE_TX
    }

    private static final String READ = """
            select pg_backend_pid() as pid,
                   current_setting('app.current_tenant_id', true) as setting,
                   count(*) as visible,
                   count(*) filter (where tenant_id is distinct from $1) as foreign_rows
              from item
            """;

    /** One request's result; {@code read} is false for kinds that only bind, fail or abandon. */
    record Outcome(Kind kind, UUID tenant, boolean read, int pid, String setting, long visible, long foreign,
            String error) {

        static Outcome noRead(Kind kind, UUID tenant) {
            return new Outcome(kind, tenant, false, 0, null, 0, 0, null);
        }
    }

    private LeakProbe() {}

    static boolean run(Pool pool, List<UUID> tenants, int itemsPerTenant, int requests, int inFlight, Mode mode,
            long seed) throws InterruptedException {
        pool.query("delete from probe_commit").execute().await().indefinitely();
        Random random = new Random(seed);
        List<Kind> kinds = new ArrayList<>(requests);
        List<UUID> picks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            int roll = random.nextInt(100);
            kinds.add(roll < 55 ? Kind.BOUND : roll < 70 ? Kind.UNBOUND : roll < 77 ? Kind.UNBOUND_TX
                    : roll < 83 ? Kind.FAILING : roll < 89 ? Kind.TIMED_OUT : roll < 95 ? Kind.ABANDONED
                    : Kind.BOUND_OUTSIDE_TX);
            picks.add(tenants.get(random.nextInt(tenants.size())));
        }

        long started = System.nanoTime();
        List<Outcome> outcomes = Multi.createFrom().range(0, requests)
                .onItem().transformToUni(i -> request(pool, kinds.get(i), picks.get(i), mode))
                .merge(inFlight)
                .collect().asList()
                .await().indefinitely();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        Map<Kind, Integer> leaks = new EnumMap<>(Kind.class);
        Map<Integer, Integer> perPid = new HashMap<>();
        Map<String, Integer> errors = new HashMap<>();
        List<Outcome> examples = new ArrayList<>();
        int handovers = 0;
        Map<Integer, Kind> lastKindOnPid = new HashMap<>();
        for (Outcome o : outcomes) {
            counts.merge(o.kind(), 1, Integer::sum);
            if (o.error() != null) {
                errors.merge(o.kind() + ": " + o.error(), 1, Integer::sum);
                continue;
            }
            if (!o.read()) {
                continue;
            }
            perPid.merge(o.pid(), 1, Integer::sum);
            Kind previous = lastKindOnPid.put(o.pid(), o.kind());
            if (previous == Kind.BOUND && o.kind() != Kind.BOUND) {
                handovers++;
            }
            if (leaked(o, itemsPerTenant)) {
                leaks.merge(o.kind(), 1, Integer::sum);
                if (examples.size() < 3) {
                    examples.add(o);
                }
            }
        }
        int totalLeaks = leaks.values().stream().mapToInt(Integer::intValue).sum();

        Log.out("probe: mode=%s requests=%d in-flight=%d seed=%d elapsed=%d ms", mode, requests, inFlight, seed,
                elapsedMs);
        Log.out("probe: kinds %s", counts);
        Log.out("probe: %d physical connections served the reads; reads per connection min %d max %d",
                perPid.size(), perPid.values().stream().mapToInt(Integer::intValue).min().orElse(0),
                perPid.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        Log.out("probe: %d reads came straight after a BOUND read on the same connection (completion order)",
                handovers);
        errors.forEach((error, n) -> Log.out("probe: expected failure x%d  %s", n, error));
        if (mode == Mode.LOCAL) {
            Thread.sleep(500); // let the timed-out transactions, which the client did not stop, finish
            long committed = pool.query("select count(*) from probe_commit").execute().await().indefinitely()
                    .iterator().next().getLong(0);
            Log.out("probe: TIMED_OUT requests %d, of which committed their write anyway: %d",
                    counts.getOrDefault(Kind.TIMED_OUT, 0), committed);
        }
        Log.out("probe: LEAKS %d %s", totalLeaks, leaks);
        examples.forEach(o -> Log.out("probe: leak example %s", o));
        return totalLeaks == 0;
    }

    private static boolean leaked(Outcome o, int itemsPerTenant) {
        if (o.kind() == Kind.BOUND && o.read()) {
            return o.foreign() != 0 || o.visible() != itemsPerTenant || !o.tenant().toString().equals(o.setting());
        }
        // Not bound: no rows, and no tenant left in the setting (null, or '' once a session has set it).
        return o.visible() != 0 || (o.setting() != null && !o.setting().isEmpty());
    }

    private static Uni<Outcome> request(Pool pool, Kind kind, UUID tenant, Mode mode) {
        Uni<Outcome> uni = switch (kind) {
            case BOUND -> mode == Mode.TEXTUAL
                    // begin and the binding as SQL text on a bare pool statement: never committed.
                    ? pool.query("begin; select set_config('app.current_tenant_id', '" + tenant + "', true)")
                            .execute().replaceWith(Outcome.noRead(kind, tenant))
                    : pool.withTransaction(c -> bind(c, tenant, mode).chain(() -> read(c, kind, tenant)));
            case UNBOUND -> read(pool, kind, tenant);
            case UNBOUND_TX -> pool.withTransaction(c -> read(c, kind, tenant));
            case FAILING -> pool.withTransaction(c -> bind(c, tenant, mode)
                    .chain(() -> c.query("select 1 / 0").execute())
                    .replaceWith(Outcome.noRead(kind, tenant)));
            case TIMED_OUT -> pool.withTransaction(c -> bind(c, tenant, mode)
                            .chain(() -> c.query("select pg_sleep(0.02)").execute())
                            .chain(() -> c.preparedQuery("insert into probe_commit (tenant_id) values ($1)")
                                    .execute(Tuple.of(tenant)))
                            .replaceWith(Outcome.noRead(kind, tenant)))
                    .ifNoItem().after(Duration.ofMillis(5)).fail();
            case ABANDONED -> pool.getConnection().chain(c -> (mode == Mode.TEXTUAL
                            // begin as SQL text: the pool does not know the connection is in a transaction.
                            ? c.query("begin").execute().replaceWithVoid()
                            : c.begin().replaceWithVoid())
                    .chain(() -> bind(c, tenant, mode))
                    .chain(() -> c.close())
                    .replaceWith(Outcome.noRead(kind, tenant)));
            case BOUND_OUTSIDE_TX -> bind(pool, tenant, Mode.LOCAL).chain(() -> read(pool, kind, tenant));
        };
        return uni.onFailure().recoverWithItem(failure ->
                new Outcome(kind, tenant, false, 0, null, 0, 0, failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : " " + firstLine(failure.getMessage()))));
    }

    private static String firstLine(String message) {
        int nl = message.indexOf('\n');
        String line = nl < 0 ? message : message.substring(0, nl);
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    /** Binds the tenant; parameterised, unlike {@code SET LOCAL}, which takes no bind parameters. */
    private static Uni<Void> bind(SqlClient client, UUID tenant, Mode mode) {
        return client.preparedQuery("select set_config('app.current_tenant_id', $1, $2)")
                .execute(Tuple.of(tenant.toString(), mode != Mode.SESSION))
                .replaceWithVoid();
    }

    private static Uni<Outcome> read(SqlClient client, Kind kind, UUID tenant) {
        return client.preparedQuery(READ).execute(Tuple.of(tenant)).map(rows -> {
            Row row = rows.iterator().next();
            return new Outcome(kind, tenant, true, row.getInteger("pid"), row.getString("setting"),
                    row.getLong("visible"), row.getLong("foreign_rows"), null);
        });
    }
}
