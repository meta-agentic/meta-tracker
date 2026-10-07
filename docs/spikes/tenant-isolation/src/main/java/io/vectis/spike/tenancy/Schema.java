package io.vectis.spike.tenancy;

import io.vertx.mutiny.sqlclient.Pool;
import java.util.List;

/**
 * The scratch schema, created and seeded by the owner role. It copies the shape of the
 * product's {@code item} table (V1 + V2 + V3 on main at the time of the spike) and adds
 * {@code tenant_id}; boards and columns are ids only, as the board read path touches nothing
 * but {@code item}.
 *
 * <ul>
 *   <li>{@code item}: RLS enabled <em>and forced</em>, one policy keyed on the transaction's
 *       tenant setting;
 *   <li>{@code item_plain}: the same rows and indexes, no RLS: the application-filtering baseline;
 *   <li>{@code template_level}: built-in rows ({@code tenant_id} null) and tenant-owned levels;
 *   <li>{@code template_level_naive}: the same with a policy that checks only {@code tenant_id},
 *       to show what it lets through.
 * </ul>
 */
final class Schema {

    /**
     * The tenant bound to the current transaction, or null. {@code nullif} because a custom
     * setting that a session has ever set reads back as {@code ''}, not null, after the setting
     * transaction ends; {@code ''::uuid} would raise an error instead of matching nothing.
     */
    static final String CURRENT_TENANT = "nullif(current_setting('app.current_tenant_id', true), '')::uuid";

    /** The one built-in template level, {@code scrum} version 1. */
    static final String BUILT_IN = "00000000-0000-7000-8000-0000000000b1";

    private Schema() {}

    static void create(Pool owner, int tenants, int itemsPerTenant) {
        List<String> ddl = List.of(
                "drop table if exists item, item_plain, template_level, template_level_naive, bench_target, tenant cascade",
                "create table tenant (id uuid primary key, name text not null)",
                """
                create table item (
                    id           uuid        primary key,
                    tenant_id    uuid        not null references tenant (id),
                    workspace_id uuid        not null,
                    board_id     uuid        not null,
                    column_id    uuid        not null,
                    key          text        not null,
                    title        text        not null,
                    rank         text        not null,
                    fields       jsonb       not null default '{}'::jsonb,
                    sprint_id    uuid,
                    version      bigint      not null default 1,
                    created_at   timestamptz not null default now(),
                    updated_at   timestamptz not null default now()
                )""",
                "create table bench_target (tenant_id uuid, workspace_id uuid, board_id uuid, c0 uuid, c1 uuid, c2 uuid)",
                """
                insert into tenant (id, name)
                select gen_random_uuid(), 'tenant ' || g from generate_series(1, %d) g""".formatted(tenants),
                """
                insert into bench_target
                select id, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid()
                  from tenant""",
                """
                insert into item (id, tenant_id, workspace_id, board_id, column_id, key, title, rank, fields)
                select gen_random_uuid(), b.tenant_id, b.workspace_id, b.board_id,
                       (array[b.c0, b.c1, b.c2])[1 + g %% 3],
                       'T-' || g, 'Item ' || g, lpad(g::text, 6, '0'),
                       jsonb_build_object('storyPoints', g %% 8, 'type', 'story')
                  from bench_target b, generate_series(1, %d) g""".formatted(itemsPerTenant),
                "create table item_plain (like item including all)",
                "insert into item_plain select * from item",
                // The product's board read index, on both tables, plus a tenant index.
                "create index item_board_column_rank_idx on item (board_id, column_id, rank)",
                "create index item_tenant_idx on item (tenant_id)",
                "create index item_plain_board_column_rank_idx on item_plain (board_id, column_id, rank)",
                "create index item_plain_tenant_idx on item_plain (tenant_id)",
                // FORCE: the owner is subject to the policy too. Without it, any connection that
                // happens to use the owner role (a migration datasource, say) reads every tenant.
                "alter table item enable row level security",
                "alter table item force row level security",
                """
                create policy item_tenant on item
                    using (tenant_id = %1$s)
                    with check (tenant_id = %1$s)""".formatted(CURRENT_TENANT),
                """
                create table template_level (
                    id        uuid primary key,
                    tenant_id uuid references tenant (id),
                    key       text not null,
                    version   int  not null,
                    parent_id uuid references template_level (id),
                    document  jsonb not null default '{}'::jsonb
                )""",
                "create table template_level_naive (like template_level including all)",
                "alter table template_level_naive add foreign key (parent_id) references template_level_naive (id)",
                // Built-ins are released, not written by tenants: seeded before RLS is forced.
                "insert into template_level (id, tenant_id, key, version) values ('%s', null, 'scrum', 1)"
                        .formatted(BUILT_IN),
                "insert into template_level_naive (id, tenant_id, key, version) values ('%s', null, 'scrum', 1)"
                        .formatted(BUILT_IN),
                "alter table template_level enable row level security",
                "alter table template_level force row level security",
                "alter table template_level_naive enable row level security",
                "alter table template_level_naive force row level security",
                // Built-ins (tenant_id null) are readable by every tenant, bound or not; tenant
                // levels only by their tenant.
                "create policy template_read on template_level for select using (tenant_id is null or tenant_id = %s)"
                        .formatted(CURRENT_TENANT),
                // A tenant writes only its own levels, and extends only a level it can see: the
                // foreign key alone would accept another tenant's id, because referential checks
                // do not apply RLS. The subquery runs under the read policy.
                """
                create policy template_insert on template_level for insert
                    with check (tenant_id = %1$s
                                and (parent_id is null
                                     or exists (select 1 from template_level p where p.id = template_level.parent_id)))"""
                        .formatted(CURRENT_TENANT),
                """
                create policy template_update on template_level for update
                    using (tenant_id = %1$s) with check (tenant_id = %1$s)""".formatted(CURRENT_TENANT),
                "create policy template_delete on template_level for delete using (tenant_id = %s)"
                        .formatted(CURRENT_TENANT),
                "create policy naive_read on template_level_naive for select using (tenant_id is null or tenant_id = %s)"
                        .formatted(CURRENT_TENANT),
                "create policy naive_write on template_level_naive for insert with check (tenant_id = %s)"
                        .formatted(CURRENT_TENANT),
                "grant select on tenant, bench_target to spike_app",
                "grant select, insert, update, delete on item, item_plain, template_level, template_level_naive to spike_app",
                "analyze tenant, item, item_plain, bench_target, template_level, template_level_naive");
        owner.withTransaction(conn -> {
            var chain = conn.query("select 1").execute();
            for (String statement : ddl) {
                chain = chain.chain(ignored -> conn.query(statement).execute());
            }
            return chain;
        }).await().indefinitely();
        Log.out("schema: %d tenants x %d items = %d rows in item and in item_plain",
                tenants, itemsPerTenant, (long) tenants * itemsPerTenant);
    }
}
