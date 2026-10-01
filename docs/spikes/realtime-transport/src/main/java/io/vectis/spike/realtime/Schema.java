package io.vectis.spike.realtime;

import io.quarkus.runtime.StartupEvent;
import io.vertx.mutiny.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The spike's schema. A cut-down {@code workspace} and {@code item} from V1/V2, plus the
 * three things the transport decision adds:
 *
 * <ul>
 *   <li>{@code item.version}: a per-item counter bumped by every write, the convergence key;
 *   <li>{@code workspace.event_seq}: a per-workspace counter bumped under the workspace row
 *       lock, so sequences are gap-free and commit in order;
 *   <li>{@code workspace_event}: the short-retention log the doorbell points into, and the
 *       source of {@code Last-Event-ID} replay. The payload is {@code text}, not
 *       {@code jsonb}, so a replayed frame is byte-identical to the live one.
 * </ul>
 *
 * <p>Both instances start concurrently against one database, so the DDL runs under a
 * transaction-scoped advisory lock.
 */
@ApplicationScoped
public class Schema {

    private static final String DDL = """
            create table if not exists workspace (
                id              uuid   primary key,
                key             text   not null unique,
                name            text   not null,
                template_key    text   not null,
                config_revision bigint not null default 0,
                event_seq       bigint not null default 0,
                item_counter    bigint not null default 0
            );
            create table if not exists item (
                id           uuid        primary key,
                workspace_id uuid        not null references workspace (id) on delete cascade,
                board_id     uuid        not null,
                column_id    uuid        not null,
                key          text        not null,
                title        text        not null,
                rank         text        not null,
                fields       jsonb       not null default '{}'::jsonb,
                sprint_id    uuid,
                version      bigint      not null default 1,
                created_at   timestamptz not null default now(),
                updated_at   timestamptz not null default now(),
                unique (workspace_id, key)
            );
            create table if not exists workspace_event (
                workspace_id uuid        not null references workspace (id) on delete cascade,
                seq          bigint      not null,
                type         text        not null,
                payload      text        not null,
                created_at   timestamptz not null default now(),
                primary key (workspace_id, seq)
            );
            """;

    private final Pool pool;

    public Schema(Pool pool) {
        this.pool = pool;
    }

    void onStart(@Observes StartupEvent event) {
        pool.withTransaction(conn -> conn.query("select pg_advisory_xact_lock(4646)").execute()
                        .flatMap(ignored -> conn.query(DDL).execute()))
                .await().indefinitely();
    }
}
