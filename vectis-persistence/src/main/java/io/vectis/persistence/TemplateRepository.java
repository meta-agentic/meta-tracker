// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.template.TemplateJson;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-blocking reads of the published template hierarchy.
 *
 * <p>A tenant sees the built-ins and its own templates; {@code tenantId} null sees the built-ins
 * only. Template rows carry no tenant policy yet (VEC-80), so the visibility rule is applied
 * here, in every query. Documents are read as text and parsed by the domain's
 * {@link TemplateJson}, so the resolver gets the number and map types it is written against.
 */
@ApplicationScoped
public class TemplateRepository {

    private final Pool pool;

    public TemplateRepository(Pool pool) {
        this.pool = pool;
    }

    /** The templates {@code tenantId} can see, built-ins first, then by key, each at its latest version. */
    public Uni<List<TemplateSummary>> findVisible(UUID tenantId) {
        return pool.preparedQuery("""
                        select t.id, t.key, t.tenant_id, t.follow, v.version, p.key as parent_key, v.parent_version
                          from workflow_template t
                          join lateral (select version, parent_id, parent_version
                                          from workflow_template_version
                                         where template_id = t.id
                                         order by version desc
                                         limit 1) v on true
                          left join workflow_template p on p.id = v.parent_id
                         where t.tenant_id is null or t.tenant_id = $1
                         order by t.tenant_id nulls first, t.key
                        """)
                .execute(Tuple.tuple().addUUID(tenantId))
                .map(rows -> {
                    List<TemplateSummary> out = new ArrayList<>(rows.size());
                    rows.forEach(row -> out.add(new TemplateSummary(
                            row.getUUID("id"),
                            row.getString("key"),
                            row.getUUID("tenant_id"),
                            new TemplateRef(row.getString("key"), row.getInteger("version")),
                            parent(row),
                            row.getString("follow"))));
                    return List.copyOf(out);
                });
    }

    /** The version {@code ref} names among the templates {@code tenantId} can see, or empty. */
    public Uni<Optional<PublishedTemplate>> findVersion(UUID tenantId, TemplateRef ref) {
        return findVersion(pool, tenantId, ref);
    }

    /** As {@link #findVersion(UUID, TemplateRef)}, on {@code client}, inside the caller's transaction. */
    Uni<Optional<PublishedTemplate>> findVersion(SqlClient client, UUID tenantId, TemplateRef ref) {
        return client.preparedQuery("""
                        select t.id, t.key, t.tenant_id, v.version, p.key as parent_key, v.parent_version,
                               v.document::text as document, v.resolved::text as resolved,
                               v.resolver_version, v.cause::text as cause, v.published_at
                          from workflow_template t
                          join workflow_template_version v on v.template_id = t.id
                          left join workflow_template p on p.id = v.parent_id
                         where (t.tenant_id is null or t.tenant_id = $1)
                           and t.key = $2
                           and v.version = $3
                        """)
                .execute(Tuple.tuple().addUUID(tenantId).addString(ref.key()).addInteger(ref.version()))
                .map(rows -> {
                    if (rows.rowCount() == 0) {
                        return Optional.<PublishedTemplate>empty();
                    }
                    Row row = rows.iterator().next();
                    return Optional.of(new PublishedTemplate(
                            row.getUUID("id"),
                            ref,
                            row.getUUID("tenant_id"),
                            parent(row),
                            TemplateJson.parseObject(row.getString("document")),
                            TemplateJson.parseObject(row.getString("resolved")),
                            row.getInteger("resolver_version"),
                            TemplateJson.parseObject(row.getString("cause")),
                            row.getOffsetDateTime("published_at")));
                });
    }

    private static TemplateRef parent(Row row) {
        String key = row.getString("parent_key");
        return key == null ? null : new TemplateRef(key, row.getInteger("parent_version"));
    }
}
