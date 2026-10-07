package io.vectis.spike.tenancy;

import io.quarkus.reactive.datasource.ReactiveDataSource;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@code java -jar target/quarkus-app/quarkus-run.jar <command>}; run.sh drives it.
 *
 * <ul>
 *   <li>{@code setup}: create and seed the scratch schema as the owner role;
 *   <li>{@code probe}: the leak probe with transaction-local binding (exit 1 on any leak);
 *   <li>{@code control}: the leak probe with session binding, which must leak;
 *   <li>{@code templates}: the template-level policy checks;
 *   <li>{@code bench}: the read-path benchmark, sequential and at 16 in flight, and its plan.
 * </ul>
 */
@QuarkusMain
public class Main implements QuarkusApplication {

    @Inject Pool app;

    @Inject @ReactiveDataSource("owner") Pool owner;

    @ConfigProperty(name = "spike.tenants", defaultValue = "1000") int tenants;
    @ConfigProperty(name = "spike.items-per-tenant", defaultValue = "100") int itemsPerTenant;
    @ConfigProperty(name = "spike.probe.requests", defaultValue = "50000") int probeRequests;
    @ConfigProperty(name = "spike.probe.in-flight", defaultValue = "200") int probeInFlight;
    @ConfigProperty(name = "spike.bench.per-round", defaultValue = "2000") int benchPerRound;
    @ConfigProperty(name = "spike.bench.rounds", defaultValue = "5") int benchRounds;
    @ConfigProperty(name = "spike.seed", defaultValue = "14") long seed;

    @Override
    public int run(String... args) {
        String command = args.length == 0 ? "probe" : args[0];
        Log.out("postgres: %s", app.query("select version()").execute().await().indefinitely()
                .iterator().next().getString(0));
        Log.out("role: %s", owner.query("""
                        select string_agg(rolname || ' super=' || rolsuper || ' bypassrls=' || rolbypassrls, ', ')
                          from pg_roles where rolname in ('spike_app', 'spike_owner')""")
                .execute().await().indefinitely().iterator().next().getString(0));
        return switch (command) {
            case "setup" -> {
                Schema.create(owner, tenants, itemsPerTenant);
                yield 0;
            }
            case "probe" -> LeakProbe.run(app, tenantIds(), itemsPerTenant, probeRequests, probeInFlight,
                    LeakProbe.Binding.LOCAL, seed) ? 0 : 1;
            case "control" -> LeakProbe.run(app, tenantIds(), itemsPerTenant, probeRequests / 10, probeInFlight,
                    LeakProbe.Binding.SESSION, seed) ? 1 : 0; // the control passes when it leaks
            case "templates" -> {
                List<UUID> ids = tenantIds();
                yield TemplateProbe.run(app, ids.get(0), ids.get(1)) ? 0 : 1;
            }
            case "bench" -> {
                List<Bench.Target> targets = targets();
                Bench.explain(app, targets.getFirst());
                Bench.run(app, targets, benchPerRound, benchRounds, 1, seed);
                Bench.run(app, targets, benchPerRound, benchRounds, 16, seed + 1);
                yield 0;
            }
            default -> {
                Log.out("unknown command %s", command);
                yield 2;
            }
        };
    }

    private List<UUID> tenantIds() {
        List<UUID> ids = new ArrayList<>();
        app.query("select id from tenant order by id").execute().await().indefinitely()
                .forEach(row -> ids.add(row.getUUID("id")));
        return ids;
    }

    private List<Bench.Target> targets() {
        List<Bench.Target> out = new ArrayList<>();
        app.query("select tenant_id, board_id, c0, c1, c2 from bench_target").execute().await().indefinitely()
                .forEach(row -> {
                    for (String c : List.of("c0", "c1", "c2")) {
                        out.add(new Bench.Target(row.getUUID("tenant_id"), row.getUUID("board_id"), row.getUUID(c)));
                    }
                });
        return out;
    }
}
