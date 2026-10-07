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
 * <p>The {@code session} binding is the negative control: {@code set_config(..., false)} is a
 * plain {@code SET}, which outlives the transaction and stays on the pooled connection. The
 * probe must report leaks for it, or it could not detect one.
 */
final class LeakProbe {

    enum Binding { LOCAL, SESSION }

    enum Kind {
        /** set_config(..., true) inside pool.withTransaction, then read: the design under test. */
        BOUND,
        /** a plain pool query, no binding at all: must see nothing. */
        UNBOUND,
        /** pool.withTransaction with no binding: must see nothing. */
        UNBOUND_TX,
        /** binds, then fails inside the transaction: rolled back. */
        FAILING,
        /** binds, then is abandoned by its subscriber mid-transaction (a request timeout). */
        CANCELLED,
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

    record Outcome(Kind kind, UUID tenant, int pid, String setting, long visible, long foreign, String error) {}

    private LeakProbe() {}

    static boolean run(Pool pool, List<UUID> tenants, int itemsPerTenant, int requests, int inFlight,
            Binding binding, long seed) {
        Random random = new Random(seed);
        List<Kind> kinds = new ArrayList<>(requests);
        List<UUID> picks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            int roll = random.nextInt(100);
            kinds.add(roll < 60 ? Kind.BOUND : roll < 75 ? Kind.UNBOUND : roll < 82 ? Kind.UNBOUND_TX
                    : roll < 89 ? Kind.FAILING : roll < 95 ? Kind.CANCELLED : Kind.BOUND_OUTSIDE_TX);
            picks.add(tenants.get(random.nextInt(tenants.size())));
        }

        long started = System.nanoTime();
        List<Outcome> outcomes = Multi.createFrom().range(0, requests)
                .onItem().transformToUni(i -> request(pool, kinds.get(i), picks.get(i), binding))
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

        Log.out("probe: binding=%s requests=%d in-flight=%d seed=%d elapsed=%d ms", binding, requests, inFlight,
                seed, elapsedMs);
        Log.out("probe: kinds %s", counts);
        Log.out("probe: %d physical connections served the reads; requests per connection min %d max %d",
                perPid.size(), perPid.values().stream().mapToInt(Integer::intValue).min().orElse(0),
                perPid.values().stream().mapToInt(Integer::intValue).max().orElse(0));
        Log.out("probe: %d reads came straight after a BOUND request on the same connection (completion order)",
                handovers);
        errors.forEach((error, n) -> Log.out("probe: expected failure x%d  %s", n, error));
        Log.out("probe: LEAKS %d %s", totalLeaks, leaks);
        examples.forEach(o -> Log.out("probe: leak example %s", o));
        return totalLeaks == 0;
    }

    private static boolean leaked(Outcome o, int itemsPerTenant) {
        boolean bound = o.kind() == Kind.BOUND || o.kind() == Kind.CANCELLED;
        if (bound) {
            return o.foreign() != 0 || o.visible() != itemsPerTenant || !o.tenant().toString().equals(o.setting());
        }
        // Not bound: no rows, and no tenant left in the setting (null, or '' once a session has set it).
        return o.visible() != 0 || (o.setting() != null && !o.setting().isEmpty());
    }

    private static Uni<Outcome> request(Pool pool, Kind kind, UUID tenant, Binding binding) {
        Uni<Outcome> uni = switch (kind) {
            case BOUND -> pool.withTransaction(c -> bind(c, tenant, binding).chain(() -> read(c, kind, tenant)));
            case UNBOUND -> read(pool, kind, tenant);
            case UNBOUND_TX -> pool.withTransaction(c -> read(c, kind, tenant));
            case FAILING -> pool.withTransaction(c -> bind(c, tenant, binding)
                    .chain(() -> c.query("select 1 / 0").execute())
                    .replaceWith(new Outcome(kind, tenant, 0, null, 0, 0, null)));
            case CANCELLED -> pool.withTransaction(c -> bind(c, tenant, binding)
                            .chain(() -> c.query("select pg_sleep(0.02)").execute())
                            .chain(() -> read(c, kind, tenant)))
                    .ifNoItem().after(Duration.ofMillis(5)).fail();
            case BOUND_OUTSIDE_TX -> bind(pool, tenant, Binding.LOCAL).chain(() -> read(pool, kind, tenant));
        };
        return uni.onFailure().recoverWithItem(failure ->
                new Outcome(kind, tenant, 0, null, 0, 0, failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : " " + firstLine(failure.getMessage()))));
    }

    private static String firstLine(String message) {
        int nl = message.indexOf('\n');
        String line = nl < 0 ? message : message.substring(0, nl);
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    /** Binds the tenant; parameterised, unlike {@code SET LOCAL}, which takes no bind parameters. */
    private static Uni<Void> bind(SqlClient client, UUID tenant, Binding binding) {
        return client.preparedQuery("select set_config('app.current_tenant_id', $1, $2)")
                .execute(Tuple.of(tenant.toString(), binding == Binding.LOCAL))
                .replaceWithVoid();
    }

    private static Uni<Outcome> read(SqlClient client, Kind kind, UUID tenant) {
        return client.preparedQuery(READ).execute(Tuple.of(tenant)).map(rows -> {
            Row row = rows.iterator().next();
            return new Outcome(kind, tenant, row.getInteger("pid"), row.getString("setting"),
                    row.getLong("visible"), row.getLong("foreign_rows"), null);
        });
    }
}
