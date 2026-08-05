package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Sprint;
import io.vectis.domain.SprintStatus;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Non-blocking reads and writes for {@link Sprint}. */
@ApplicationScoped
public class SprintRepository {

    private static final String SINGLE_ACTIVE_CONSTRAINT = "sprint_board_single_active_idx";

    private final Pool pool;

    public SprintRepository(Pool pool) {
        this.pool = pool;
    }

    public Uni<Sprint> insert(Sprint sprint) {
        return pool.preparedQuery("""
                        insert into sprint (id, board_id, name, status, started_at, completed_at)
                        values ($1, $2, $3, $4, $5, $6)
                        """)
                .execute(Tuple.of(
                        sprint.id(),
                        sprint.boardId(),
                        sprint.name(),
                        sprint.status().name(),
                        toOffset(sprint.startedAt()),
                        toOffset(sprint.completedAt())))
                .replaceWith(sprint);
    }

    public Uni<Optional<Sprint>> findById(UUID id) {
        return pool.preparedQuery(
                        "select id, board_id, name, status, started_at, completed_at from sprint where id = $1")
                .execute(Tuple.of(id))
                .map(rows -> rows.rowCount() == 0
                        ? Optional.<Sprint>empty()
                        : Optional.of(map(rows.iterator().next())));
    }

    public Uni<List<Sprint>> listByBoard(UUID boardId) {
        return pool.preparedQuery("""
                        select id, board_id, name, status, started_at, completed_at
                          from sprint
                         where board_id = $1
                         order by id
                        """)
                .execute(Tuple.of(boardId))
                .map(rows -> {
                    List<Sprint> out = new ArrayList<>(rows.size());
                    rows.forEach(row -> out.add(map(row)));
                    return List.copyOf(out);
                });
    }

    /**
     * Persists a FUTURE→ACTIVE transition. The single-active-sprint invariant is
     * enforced by {@code sprint_board_single_active_idx}, a partial unique index, rather
     * than checked here first — a check-then-write would leave a race window between two
     * concurrent starts on the same board. A violation surfaces as
     * {@link ActiveSprintConflictException}.
     */
    public Uni<Sprint> start(Sprint sprint) {
        return pool.preparedQuery("update sprint set status = $1, started_at = $2 where id = $3")
                .execute(Tuple.of(sprint.status().name(), toOffset(sprint.startedAt()), sprint.id()))
                .replaceWith(sprint)
                .onFailure(this::isSingleActiveViolation)
                .transform(failure -> new ActiveSprintConflictException(sprint.boardId(), failure));
    }

    private boolean isSingleActiveViolation(Throwable failure) {
        return failure instanceof PgException pg && SINGLE_ACTIVE_CONSTRAINT.equals(pg.getConstraint());
    }

    /**
     * Persists an ACTIVE→COMPLETED transition and, in the same transaction, moves the
     * given item ids to their destination — a sprint, or the backlog if
     * {@code destinationSprintId} is null. Which items are "unfinished" is not this
     * repository's decision: {@link Sprint#complete()} deliberately has no notion of
     * done, since that belongs to the workflow engine VEC-15 owns. The caller supplies
     * the exact set.
     */
    public Uni<Sprint> complete(Sprint sprint, List<UUID> itemIdsToMove, UUID destinationSprintId) {
        return pool.withTransaction(conn -> conn.preparedQuery(
                        "update sprint set status = $1, completed_at = $2 where id = $3")
                .execute(Tuple.of(sprint.status().name(), toOffset(sprint.completedAt()), sprint.id()))
                .chain(ignored -> {
                    if (itemIdsToMove.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    return conn.preparedQuery(
                                    "update item set sprint_id = $1, updated_at = now() where id = any($2)")
                            .execute(Tuple.of(destinationSprintId, itemIdsToMove.toArray(UUID[]::new)))
                            .replaceWithVoid();
                })
                .replaceWith(sprint));
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Sprint map(Row row) {
        OffsetDateTime startedAt = row.getOffsetDateTime("started_at");
        OffsetDateTime completedAt = row.getOffsetDateTime("completed_at");
        return new Sprint(
                row.getUUID("id"),
                row.getUUID("board_id"),
                row.getString("name"),
                SprintStatus.valueOf(row.getString("status")),
                startedAt == null ? null : startedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }
}
