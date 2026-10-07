package io.vectis.spike.tenancy;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The template constraints from ADR-VEC-03 under the same policy design: built-in levels are
 * readable by every tenant and writable by none; a tenant's levels are scoped like its
 * workspaces; and a tenant extends only a built-in or its own level, never another tenant's.
 * Each check prints PASS or FAIL with what was observed.
 */
final class TemplateProbe {

    private TemplateProbe() {}

    static boolean run(Pool pool, UUID tenantA, UUID tenantB) {
        UUID builtIn = UUID.fromString(Schema.BUILT_IN);
        UUID levelA = UUID.randomUUID();
        UUID levelB = UUID.randomUUID();
        as(pool, tenantA, c -> insert(c, "template_level", levelA, tenantA, builtIn));
        as(pool, tenantB, c -> insert(c, "template_level", levelB, tenantB, builtIn));
        as(pool, tenantB, c -> insert(c, "template_level_naive", levelB, tenantB, builtIn));

        boolean ok = true;
        ok &= check("tenant A sees the built-in and its own level, not B's",
                List.of(builtIn, levelA).equals(visible(pool, tenantA, "template_level")),
                visible(pool, tenantA, "template_level"));
        ok &= check("unbound sees the built-in only",
                List.of(builtIn).equals(visible(pool, null, "template_level")), visible(pool, null, "template_level"));
        ok &= check("tenant A cannot write a built-in level (tenant_id null)",
                refused(pool, tenantA, c -> insert(c, "template_level", UUID.randomUUID(), null, null)), "");
        ok &= check("tenant A cannot update the built-in",
                updated(pool, tenantA, builtIn) == 0, "rows updated: " + updated(pool, tenantA, builtIn));
        ok &= check("tenant A cannot write a level owned by B",
                refused(pool, tenantA, c -> insert(c, "template_level", UUID.randomUUID(), tenantB, builtIn)), "");
        ok &= check("tenant A cannot extend B's level (policy checks the parent is visible)",
                refused(pool, tenantA, c -> insert(c, "template_level", UUID.randomUUID(), tenantA, levelB)), "");
        boolean naiveAccepted = !refused(pool, tenantA,
                c -> insert(c, "template_level_naive", UUID.randomUUID(), tenantA, levelB));
        Log.out("templates: %s  with a policy on tenant_id alone, tenant A %s extend B's level"
                        + " (a foreign key check does not apply RLS)",
                naiveAccepted ? "SHOWN" : "NOT SHOWN", naiveAccepted ? "CAN" : "cannot");
        return ok;
    }

    private static boolean check(String what, boolean passed, Object observed) {
        Log.out("templates: %s  %s %s", passed ? "PASS" : "FAIL", what, passed ? "" : "observed " + observed);
        return passed;
    }

    private static Uni<Void> insert(SqlConnection c, String table, UUID id, UUID tenant, UUID parent) {
        return c.preparedQuery("insert into " + table + " (id, tenant_id, key, version, parent_id) values ($1, $2, $3, 1, $4)")
                .execute(Tuple.of(id, tenant, "team-" + id.toString().substring(0, 8), parent))
                .replaceWithVoid();
    }

    private static List<UUID> visible(Pool pool, UUID tenant, String table) {
        return as(pool, tenant, c -> c.query("select id from " + table + " order by tenant_id nulls first, id").execute()
                .map(rows -> {
                    List<UUID> ids = new java.util.ArrayList<>();
                    rows.forEach(r -> ids.add(r.getUUID("id")));
                    return ids;
                }));
    }

    private static int updated(Pool pool, UUID tenant, UUID id) {
        return as(pool, tenant, c -> c.preparedQuery("update template_level set version = version + 1 where id = $1")
                .execute(Tuple.of(id)).map(rows -> rows.rowCount()));
    }

    private static boolean refused(Pool pool, UUID tenant, Function<SqlConnection, Uni<Void>> write) {
        try {
            as(pool, tenant, write);
            return false;
        } catch (RuntimeException expected) {
            Log.out("templates:       refused: %s", firstLine(expected.getMessage()));
            return true;
        }
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }

    /** Runs {@code work} in a transaction bound to {@code tenant}, or unbound if null. */
    private static <T> T as(Pool pool, UUID tenant, Function<SqlConnection, Uni<T>> work) {
        return pool.withTransaction(c -> (tenant == null
                        ? Uni.createFrom().voidItem()
                        : c.preparedQuery("select set_config('app.current_tenant_id', $1, true)")
                                .execute(Tuple.of(tenant.toString())).replaceWithVoid())
                .chain(() -> work.apply(c)))
                .await().indefinitely();
    }
}
