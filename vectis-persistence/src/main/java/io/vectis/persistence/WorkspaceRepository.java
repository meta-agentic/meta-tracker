package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Workspace;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Non-blocking reads and writes for {@link Workspace}. */
@ApplicationScoped
public class WorkspaceRepository {

    private final Pool pool;

    public WorkspaceRepository(Pool pool) {
        this.pool = pool;
    }

    public Uni<Workspace> insert(Workspace workspace) {
        return pool.preparedQuery("insert into workspace (id, key, name) values ($1, $2, $3)")
                .execute(Tuple.of(workspace.id(), workspace.key(), workspace.name()))
                .replaceWith(workspace);
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
                    List<Workspace> out = new java.util.ArrayList<>(rows.size());
                    rows.forEach(row -> out.add(map(row)));
                    return List.copyOf(out);
                });
    }

    private static Workspace map(Row row) {
        return new Workspace(row.getUUID("id"), row.getString("key"), row.getString("name"));
    }
}
