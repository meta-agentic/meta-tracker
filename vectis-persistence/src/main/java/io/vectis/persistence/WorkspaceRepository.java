package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Workspace;
import io.vectis.domain.template.Diff;
import io.vectis.domain.template.Resolution;
import io.vectis.domain.template.TemplateJson;
import io.vectis.domain.template.TemplateResolver;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Non-blocking reads and writes for {@link Workspace}. */
@ApplicationScoped
public class WorkspaceRepository {

    private final Pool pool;
    private final TemplateRepository templates;

    public WorkspaceRepository(Pool pool, TemplateRepository templates) {
        this.pool = pool;
        this.templates = templates;
    }

    /**
     * Creates a workspace from a template version and an optional starting delta, in one
     * transaction: the workspace row, pinned to {@code template} with the delta, then one board
     * named after the workspace whose columns project the effective states, in order, by key.
     *
     * <p>{@code template} is a built-in or one of {@code tenantId}'s own templates (null: built-ins
     * only); any other is {@link TemplateNotFoundException}. The New Project form passes no delta;
     * an import passes the instance it brings. A delta that does not resolve to a valid workflow
     * over the template is {@link ConfigurationViolationException}, with the resolver's violations,
     * and nothing is written. The workspace row is the first row written, so the transaction holds
     * the workspace lock every later writer of the workspace takes first.
     */
    public Uni<ProvisionedWorkspace> provision(Workspace workspace, UUID tenantId, TemplateRef template,
                                               Map<String, Object> delta) {
        Map<String, Object> startingDelta = delta == null ? Map.of() : delta;
        return pool.withTransaction(conn -> templates.findVersion(conn, tenantId, template)
                .chain(found -> {
                    PublishedTemplate version = found.orElseThrow(() -> new TemplateNotFoundException(template));
                    Resolution effective = TemplateResolver.resolveWorkspace(version.resolved(), startingDelta);
                    if (!effective.ok()) {
                        throw new ConfigurationViolationException(effective.violations());
                    }
                    Board board = Board.create(workspace.id(), workspace.name(), columns(effective.effective()));
                    return conn.preparedQuery("""
                                    insert into workspace (id, key, name, template_id, template_version, config_delta)
                                    values ($1, $2, $3, $4, $5, $6::text::jsonb)
                                    """)
                            .execute(Tuple.of(workspace.id(), workspace.key(), workspace.name(),
                                    version.templateId(), template.version(), TemplateJson.compact(startingDelta)))
                            .chain(() -> BoardRepository.insert(conn, board))
                            .map(saved -> new ProvisionedWorkspace(workspace, template, startingDelta, saved));
                }));
    }

    /** One column per effective state, in {@code stateOrder}. */
    private static List<BoardColumn> columns(Map<String, Object> effective) {
        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) effective.get("states");
        List<BoardColumn> columns = new ArrayList<>();
        for (Object key : (List<?>) effective.get("stateOrder")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) states.get(key);
            columns.add(BoardColumn.create((String) key, (String) state.get("name"), columns.size(),
                    Diff.onBoard(states, (String) key)));
        }
        return columns;
    }

    public Uni<Optional<Workspace>> findById(UUID id) {
        return pool.preparedQuery("select id, key, name from workspace where id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() == 0
                        ? Optional.<Workspace>empty()
                        : Optional.of(map(rows.iterator().next())));
    }

    public Uni<Optional<Workspace>> findByKey(String key) {
        return pool.preparedQuery("select id, key, name from workspace where key = $1")
                .execute(Tuple.of(key))
                .map(rows -> rows.rowCount() == 0
                        ? Optional.<Workspace>empty()
                        : Optional.of(map(rows.iterator().next())));
    }

    /**
     * All workspaces, newest first. Ordering by the primary key is deliberate rather
     * than lazy: UUIDv7 is time-ordered, so this is a chronological sort served by the
     * primary key index with no sort step and no {@code created_at} index.
     */
    public Uni<List<Workspace>> listNewestFirst() {
        return pool.query("select id, key, name from workspace order by id desc")
                .execute()
                .map(rows -> {
                    List<Workspace> out = new ArrayList<>(rows.size());
                    rows.forEach(row -> out.add(map(row)));
                    return List.copyOf(out);
                });
    }

    private static Workspace map(Row row) {
        return new Workspace(row.getUUID("id"), row.getString("key"), row.getString("name"));
    }
}
