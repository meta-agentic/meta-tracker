package io.vectis.persistence;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.Sprint;
import io.vectis.domain.SprintStatus;
import io.vectis.domain.TimeOrderedId;
import io.vectis.persistence.WorkspaceEventLog.Change;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.pgclient.PgException;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.time.LocalDate;
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

    /** Moves a completed sprint's items, returning them in PostgreSQL's uuid order, which is the event order. */
    private static final String MOVE_ITEMS = "with moved as ("
            + " update item set sprint_id = $1, version = version + 1, updated_at = now()"
            + " where id = any($2) and workspace_id = $3 and board_id = $4"
            + " returning " + ItemRepository.SELECT_COLUMNS
            + ") select * from moved order by id";

    /**
     * Upserts imported sprints on {@code (board_id, key)}; a row is written only if something it holds differs.
     * The timestamps a sprint outside an import takes when started or completed are the source's dates here.
     */
    private static final String UPSERT_IMPORTED = """
            insert into sprint (id, board_id, key, name, status, started_at, completed_at, start_date, end_date,
                                closed_date, goal)
            select x.id, $1, x.key, x.key, x.status, x.started_at::timestamptz, x.completed_at::timestamptz,
                   x.start_date::date, x.end_date::date, x.closed_date::date, x.goal
              from unnest($2::uuid[], $3::text[], $4::text[], $5::text[], $6::text[], $7::text[], $8::text[],
                          $9::text[], $10::text[])
                   as x (id, key, status, started_at, completed_at, start_date, end_date, closed_date, goal)
            on conflict (board_id, key) do update
               set status = excluded.status, started_at = excluded.started_at, completed_at = excluded.completed_at,
                   start_date = excluded.start_date, end_date = excluded.end_date,
                   closed_date = excluded.closed_date, goal = excluded.goal
             where (sprint.status, sprint.started_at, sprint.completed_at, sprint.start_date, sprint.end_date,
                    sprint.closed_date, sprint.goal)
                   is distinct from (excluded.status, excluded.started_at, excluded.completed_at,
                                     excluded.start_date, excluded.end_date, excluded.closed_date, excluded.goal)
            returning (xmax = 0) as created
            """;

    private final Pool pool;
    private final WorkspaceEventLog log;

    public SprintRepository(Pool pool, WorkspaceEventLog log) {
        this.pool = pool;
        this.log = log;
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

    /**
     * Writes the sprints of an import into one board, creating those it does not hold and updating
     * those that differ, so running it again with the same sprints writes nothing. The board's
     * single ACTIVE sprint is respected by writing every sprint that is not ACTIVE first, so one
     * that stops being active is out of the way when its successor starts; more than one ACTIVE
     * sprint in a call is an {@link IllegalArgumentException}, as is a key named twice.
     */
    public Uni<ImportResult> upsertImported(UUID boardId, List<ImportedSprint> sprints, String origin) {
        if (sprints.stream().map(ImportedSprint::key).distinct().count() != sprints.size()
                || sprints.stream().filter(s -> s.status() == SprintStatus.ACTIVE).count() > 1) {
            return Uni.createFrom().failure(new IllegalArgumentException(
                    "an import names a sprint key twice or more than one active sprint"));
        }
        return log.writeToBoard(boardId, origin, scope -> {
            var conn = scope.connection();
            var inactive = sprints.stream().filter(s -> s.status() != SprintStatus.ACTIVE).toList();
            var active = sprints.stream().filter(s -> s.status() == SprintStatus.ACTIVE).toList();
            return upsertImported(conn, boardId, inactive)
                    .chain(first -> upsertImported(conn, boardId, active).map(second -> new ImportResult(
                            first.created() + second.created(), first.updated() + second.updated(),
                            sprints.size() - first.created() - second.created() - first.updated()
                                    - second.updated())))
                    .map(result -> Change.of(result));
        });
    }

    /**
     * Completes the board's ACTIVE sprints other than the one with {@code keepKey}, which an import is
     * about to make active, and that the import does not itself name (it writes those in order), so the single-active index cannot refuse it. Returns the key of each, or
     * its id for a sprint made in Vectis.
     */
    public Uni<List<String>> demoteActiveExcept(UUID boardId, String keepKey, List<String> importedKeys, String origin) {
        return log.writeToBoard(boardId, origin, scope -> scope.connection().preparedQuery("""
                        update sprint set status = 'COMPLETED', completed_at = coalesce(completed_at, now())
                         where board_id = $1 and status = 'ACTIVE' and key is distinct from $2
                           and (key is null or key <> all($3::text[]))
                        returning coalesce(key, id::text) as label
                        """)
                .execute(Tuple.of(boardId, keepKey, importedKeys.toArray(String[]::new)))
                .map(rows -> {
                    List<String> labels = new ArrayList<>();
                    rows.forEach(row -> labels.add(row.getString("label")));
                    return Change.of(List.copyOf(labels));
                }));
    }

    private static Uni<ImportResult> upsertImported(SqlConnection conn, UUID boardId, List<ImportedSprint> sprints) {
        if (sprints.isEmpty()) {
            return Uni.createFrom().item(new ImportResult(0, 0, 0));
        }
        int n = sprints.size();
        UUID[] ids = new UUID[n];
        String[][] columns = new String[8][n];
        for (int i = 0; i < n; i++) {
            ImportedSprint s = sprints.get(i);
            LocalDate first = firstOf(s.start(), s.end(), s.closed());
            LocalDate last = firstOf(s.closed(), s.end(), s.start());
            ids[i] = TimeOrderedId.next();
            columns[0][i] = s.key();
            columns[1][i] = s.status().name();
            columns[2][i] = s.status() == SprintStatus.FUTURE ? null : first + "T00:00:00Z";
            columns[3][i] = s.status() == SprintStatus.COMPLETED ? last + "T00:00:00Z" : null;
            columns[4][i] = s.start() == null ? null : s.start().toString();
            columns[5][i] = s.end() == null ? null : s.end().toString();
            columns[6][i] = s.closed() == null ? null : s.closed().toString();
            columns[7][i] = s.goal();
        }
        return conn.preparedQuery(UPSERT_IMPORTED)
                .execute(Tuple.from(new Object[] {boardId, ids, columns[0], columns[1], columns[2], columns[3],
                    columns[4], columns[5], columns[6], columns[7]}))
                .map(rows -> {
                    int created = 0;
                    int updated = 0;
                    for (Row row : rows) {
                        if (row.getBoolean("created")) {
                            created++;
                        } else {
                            updated++;
                        }
                    }
                    return new ImportResult(created, updated, 0);
                });
    }

    /** The first date given, or the epoch for a sprint the source dates not at all. */
    private static LocalDate firstOf(LocalDate... dates) {
        for (LocalDate date : dates) {
            if (date != null) {
                return date;
            }
        }
        return LocalDate.EPOCH;
    }

    /**
     * The keyed sprints of a workspace's boards, oldest first, with the story points they hold and
     * those of their items in the {@code done} column.
     */
    public Uni<List<SprintSummary>> summariesByWorkspace(UUID workspaceId) {
        return pool.preparedQuery("""
                        select s.key, s.name, s.status, s.start_date, s.end_date, s.closed_date, s.goal,
                               coalesce(sum((i.fields ->> 'storyPoints')::double precision), 0) as committed,
                               coalesce(sum((i.fields ->> 'storyPoints')::double precision) filter (where c.key = 'done'), 0)
                                   as done
                          from sprint s
                          join board b on b.id = s.board_id
                          left join item i on i.sprint_id = s.id
                          left join board_column c on c.id = i.column_id
                         where b.workspace_id = $1 and s.key is not null
                         group by s.id
                         order by coalesce(s.start_date, s.end_date) nulls last, length(s.key), s.key
                        """)
                .execute(Tuple.of(workspaceId))
                .map(rows -> {
                    List<SprintSummary> out = new ArrayList<>(rows.size());
                    rows.forEach(row -> out.add(new SprintSummary(row.getString("key"), row.getString("name"),
                            SprintStatus.valueOf(row.getString("status")), row.getLocalDate("start_date"),
                            row.getLocalDate("end_date"), row.getLocalDate("closed_date"), row.getString("goal"),
                            row.getDouble("committed"), row.getDouble("done"))));
                    return List.copyOf(out);
                });
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
     *
     * <p>The {@code where status = 'FUTURE'} clause is a second, independent guard: {@code
     * sprint.start()} only validates the caller's own in-memory copy, so without this a
     * stale object (or one already started/completed elsewhere) would silently re-write
     * the row anyway. A zero rowcount means the row was not in the expected state and
     * surfaces as {@link IllegalSprintTransitionException}.
     */
    public Uni<Sprint> start(Sprint sprint) {
        return pool.preparedQuery(
                        "update sprint set status = $1, started_at = $2 where id = $3 and status = 'FUTURE'")
                .execute(Tuple.of(sprint.status().name(), toOffset(sprint.startedAt()), sprint.id()))
                .onItem().transformToUni(rows -> rows.rowCount() == 0
                        ? Uni.createFrom().<Sprint>failure(new IllegalSprintTransitionException(
                                sprint.id(), SprintStatus.FUTURE, sprint.status()))
                        : Uni.createFrom().item(sprint))
                .onFailure(this::isSingleActiveViolation)
                .transform(failure -> new ActiveSprintConflictException(sprint.boardId(), failure));
    }

    private boolean isSingleActiveViolation(Throwable failure) {
        return failure instanceof PgException pg && SINGLE_ACTIVE_CONSTRAINT.equals(pg.getConstraint());
    }

    public Uni<Sprint> complete(Sprint sprint, List<UUID> itemIdsToMove, UUID destinationSprintId) {
        return complete(sprint, itemIdsToMove, destinationSprintId, null);
    }

    /**
     * Persists an ACTIVE→COMPLETED transition and, in the same transaction, moves the
     * given item ids to their destination — a sprint, or the backlog if
     * {@code destinationSprintId} is null. Which items are "unfinished" is not this
     * repository's decision: {@link Sprint#complete()} deliberately has no notion of
     * done, since that belongs to the workflow engine. The caller supplies
     * the exact set.
     *
     * <p>The {@code where status = 'ACTIVE'} clause guards the same gap as {@link #start}:
     * {@code sprint.complete()} only validates the caller's own in-memory copy, so without
     * this a stale or already-completed sprint would silently re-write (and re-stamp
     * {@code completed_at} on, or move items out from under) the row. A zero rowcount
     * fails the whole transaction with {@link IllegalSprintTransitionException} before any
     * item move is attempted — nothing is left half-applied.
     *
     * <p>The item moves are sprint assignments, so the transaction goes through
     * {@link WorkspaceEventLog#writeToBoard} on the sprint's board, whose workspace is resolved
     * under the lock: each moved item gets its {@code version} raised and one
     * {@code item.updated} event, in item id order. Only items of that board move; an id from
     * another board is left alone, as an unknown id always was, because a sprint scopes the
     * items of its own board. The destination must be a sprint of the same board that is not
     * completed, or the whole completion fails with {@link PlacementException}.
     */
    public Uni<Sprint> complete(
            Sprint sprint, List<UUID> itemIdsToMove, UUID destinationSprintId, String origin) {
        return log.writeToBoard(sprint.boardId(), origin, scope -> {
            var conn = scope.connection();
            return conn.preparedQuery("""
                            update sprint set status = $1, completed_at = $2
                             where id = $3 and board_id = $4 and status = 'ACTIVE'
                            """)
                    .execute(Tuple.of(
                            sprint.status().name(), toOffset(sprint.completedAt()), sprint.id(), sprint.boardId()))
                    .chain(rows -> {
                        if (rows.rowCount() == 0) {
                            return Uni.createFrom().failure(new IllegalSprintTransitionException(
                                    sprint.id(), SprintStatus.ACTIVE, sprint.status()));
                        }
                        if (itemIdsToMove.isEmpty()) {
                            return Uni.createFrom().item(Change.of(sprint));
                        }
                        return checkDestination(conn, sprint.boardId(), destinationSprintId)
                                .chain(() -> conn.preparedQuery(MOVE_ITEMS)
                                        .execute(Tuple.of(destinationSprintId, itemIdsToMove.toArray(UUID[]::new),
                                                scope.workspaceId(), sprint.boardId())))
                                .map(moved -> new Change<>(sprint, ItemRepository.mapAll(moved).stream()
                                        .map(item -> ItemEvents.sprintAssigned(item, scope.at()))
                                        .toList()));
                    });
        });
    }

    /** The backlog (null), or a sprint of the same board that is still open. */
    private static Uni<Void> checkDestination(SqlConnection conn, UUID boardId, UUID destinationSprintId) {
        if (destinationSprintId == null) {
            return Uni.createFrom().voidItem();
        }
        return conn.preparedQuery("select 1 from sprint where id = $1 and board_id = $2 and status <> 'COMPLETED'")
                .execute(Tuple.of(destinationSprintId, boardId))
                .invoke(rows -> {
                    if (rows.rowCount() == 0) {
                        throw new PlacementException("sprint " + destinationSprintId
                                + " is not an open sprint of board " + boardId);
                    }
                })
                .replaceWithVoid();
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
