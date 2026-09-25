package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Board;
import io.vectis.domain.Workspace;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Creates a workspace together with its initial boards.
 *
 * <p>One transaction for all of it: a workspace that exists without the board its
 * template promised is a half-provisioned tenant that the next attempt, under the same
 * key, would then collide with.
 */
@ApplicationScoped
public class WorkspaceProvisioningRepository {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String KEY_CONSTRAINT = "workspace_key_key";

    private final Pool pool;

    public WorkspaceProvisioningRepository(Pool pool) {
        this.pool = pool;
    }

    public Uni<Workspace> provision(Workspace workspace, List<Board> boards) {
        return pool.withTransaction(conn -> {
                    Uni<Void> chain = conn.preparedQuery("insert into workspace (id, key, name) values ($1, $2, $3)")
                            .execute(Tuple.of(workspace.id(), workspace.key(), workspace.name()))
                            .replaceWithVoid();
                    for (Board board : boards) {
                        chain = chain.chain(() -> BoardRepository.insertOn(conn, board).replaceWithVoid());
                    }
                    return chain.replaceWith(workspace);
                })
                .onFailure(WorkspaceProvisioningRepository::isKeyConflict)
                .transform(failure -> new WorkspaceKeyConflictException(workspace.key(), failure));
    }

    private static boolean isKeyConflict(Throwable failure) {
        return failure instanceof PgException pg
                && UNIQUE_VIOLATION.equals(pg.getSqlState())
                && KEY_CONSTRAINT.equals(pg.getConstraint());
    }
}
