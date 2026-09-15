package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.Sprint;
import io.vectis.domain.SprintStatus;
import io.vectis.domain.TimeOrderedId;
import io.vectis.domain.Workspace;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The half of the sprint mechanics that {@link SprintPersistenceTest} cannot reach by going through
 * the repository.
 *
 * <p>The story asks for the single-active-sprint rule to be "enforced server-side, not
 * just documented". A test that starts two sprints through {@link SprintRepository}
 * would still pass if the rule were a pre-check in Java, so these drive raw SQL straight
 * at the table: if the invariant were not in the schema, the smuggled rows below would
 * be accepted. The concurrency case is the reason a constraint was chosen over a check
 * in the first place, and it is the one a check-then-write would actually fail.
 */
@QuarkusTest
class SprintConstraintTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;
    @Inject SprintRepository sprints;
    @Inject Pool pool;

    /** A distinct key prefix: workspace keys are globally unique across the suite. */
    private Workspace newWorkspace() {
        String suffix = Integer.toString(KEY_SEQ.incrementAndGet());
        return workspaces.insert(Workspace.create("SC" + suffix, "Workspace " + suffix))
                .await().indefinitely();
    }

    private Board newBoard(Workspace ws) {
        return boards.insert(Board.create(ws.id(), "Delivery", List.of(
                        BoardColumn.create("Backlog", 0),
                        BoardColumn.create("Done", 1))))
                .await().indefinitely();
    }

    private Item newItem(Workspace ws, Board board, String keySuffix) {
        UUID backlog = board.orderedColumns().getFirst().id();
        return items.insert(
                        Item.create(ws.id(), board.id(), backlog, ws.key() + "-" + keySuffix, "An item", "m"))
                .await().indefinitely();
    }

    private Sprint startedSprint(Board board, String name) {
        Sprint future = sprints.insert(Sprint.create(board.id(), name)).await().indefinitely();
        return sprints.start(future.start()).await().indefinitely();
    }

    private static String causeChain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t).append('\n');
        }
        return text.toString();
    }

    @Test
    void theSingleActiveRuleLivesInTheSchemaNotOnlyInTheRepository() {
        Board board = newBoard(newWorkspace());
        startedSprint(board, "Sprint 1");

        // Deliberately bypasses SprintRepository. Were the rule only an application-level
        // pre-check, this row would be accepted and the invariant would be a convention.
        Throwable failure = assertThrows(Throwable.class, () -> pool.preparedQuery("""
                        insert into sprint (id, board_id, name, status, started_at)
                        values ($1, $2, $3, 'ACTIVE', now())
                        """)
                .execute(Tuple.of(TimeOrderedId.next(), board.id(), "smuggled in"))
                .await().indefinitely());

        assertTrue(causeChain(failure).contains("sprint_board_single_active_idx"),
                "a second active sprint must be refused by the database itself: " + causeChain(failure));
    }

    @Test
    void twoConcurrentStartsLeaveExactlyOneActiveSprint() {
        Board board = newBoard(newWorkspace());
        Sprint a = sprints.insert(Sprint.create(board.id(), "Sprint A")).await().indefinitely();
        Sprint b = sprints.insert(Sprint.create(board.id(), "Sprint B")).await().indefinitely();

        // Both subscriptions are in flight before either is joined. A check-then-write
        // could let both observe "no active sprint" and both proceed.
        CompletableFuture<Sprint> startA = sprints.start(a.start()).subscribeAsCompletionStage();
        CompletableFuture<Sprint> startB = sprints.start(b.start()).subscribeAsCompletionStage();

        int winners = 0;
        for (CompletableFuture<Sprint> attempt : List.of(startA, startB)) {
            try {
                attempt.join();
                winners++;
            } catch (RuntimeException expected) {
                // The loser of the race; the constraint rejected it.
            }
        }

        assertEquals(1, winners, "exactly one concurrent start may win");
        assertEquals(1, activeCount(board), "the board must be left with exactly one active sprint");
    }

    private long activeCount(Board board) {
        return sprints.listByBoard(board.id()).await().indefinitely().stream()
                .filter(s -> s.status() == SprintStatus.ACTIVE)
                .count();
    }

    @Test
    void aBoardMayRunSprintsBackToBack() {
        Board board = newBoard(newWorkspace());
        Sprint first = startedSprint(board, "Sprint 1");
        Sprint second = sprints.insert(Sprint.create(board.id(), "Sprint 2")).await().indefinitely();

        sprints.complete(first.complete(), List.of(), null).await().indefinitely();
        Sprint startedSecond = sprints.start(second.start()).await().indefinitely();

        // The index is partial (`where status = 'ACTIVE'`). Had that clause been omitted,
        // a completed sprint would keep blocking the board forever.
        assertEquals(SprintStatus.ACTIVE, startedSecond.status());
        assertEquals(1, activeCount(board));
    }

    @Test
    void aBoardMayHoldManyFutureAndManyCompletedSprints() {
        Board board = newBoard(newWorkspace());
        sprints.insert(Sprint.create(board.id(), "Future 1")).await().indefinitely();
        sprints.insert(Sprint.create(board.id(), "Future 2")).await().indefinitely();

        Sprint firstDone = startedSprint(board, "Done 1");
        sprints.complete(firstDone.complete(), List.of(), null).await().indefinitely();
        Sprint secondDone = startedSprint(board, "Done 2");
        sprints.complete(secondDone.complete(), List.of(), null).await().indefinitely();

        List<Sprint> all = sprints.listByBoard(board.id()).await().indefinitely();
        assertEquals(2, all.stream().filter(s -> s.status() == SprintStatus.FUTURE).count());
        assertEquals(2, all.stream().filter(s -> s.status() == SprintStatus.COMPLETED).count());
        assertEquals(0, activeCount(board));
    }

    @Test
    void completingASprintIsAtomicWithMovingItsUnfinishedItems() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Sprint active = startedSprint(board, "Sprint 1");
        Item unfinished = items.moveToSprint(newItem(ws, board, "1"), active.id()).await().indefinitely();
        UUID neverExisted = TimeOrderedId.next();

        // The destination violates item.sprint_id's foreign key, so the item move fails
        // after the sprint row has already been updated in the same transaction.
        assertThrows(Throwable.class,
                () -> sprints.complete(active.complete(), List.of(unfinished.id()), neverExisted)
                        .await().indefinitely());

        assertEquals(SprintStatus.ACTIVE,
                sprints.findById(active.id()).await().indefinitely().orElseThrow().status(),
                "a failed item move must roll the sprint completion back, not leave it half-applied");
        assertEquals(active.id(),
                items.findById(unfinished.id()).await().indefinitely().orElseThrow().sprintId(),
                "the item must stay in the sprint it was never moved out of");
    }

    @Test
    void anItemCannotBeParkedInASprintThatDoesNotExist() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Item item = newItem(ws, board, "1");

        assertThrows(Throwable.class,
                () -> items.moveToSprint(item, TimeOrderedId.next()).await().indefinitely(),
                "sprint scope is a foreign key, not a free-text label");
    }

    @Test
    void startingAnAlreadyStartedSprintIsRefused() {
        Board board = newBoard(newWorkspace());
        Sprint active = startedSprint(board, "Sprint 1");

        // A second start() call on the same in-memory-illegal transition (bypassing
        // Sprint.start()'s own FUTURE-only check) simulates a stale caller: the row is no
        // longer FUTURE, so the persistence-layer guard — not the domain object's own
        // state — must be what refuses this.
        Sprint stale = new Sprint(active.id(), active.boardId(), active.name(), SprintStatus.ACTIVE,
                active.startedAt(), null);
        IllegalSprintTransitionException failure = assertThrows(IllegalSprintTransitionException.class,
                () -> sprints.start(stale).await().indefinitely());

        assertTrue(failure.getMessage().contains(active.id().toString()));
        assertEquals(SprintStatus.ACTIVE, sprints.findById(active.id()).await().indefinitely().orElseThrow().status(),
                "a refused transition must not touch started_at or otherwise re-write the row");
    }

    @Test
    void completingAnAlreadyCompletedSprintIsRefused() {
        Board board = newBoard(newWorkspace());
        Sprint active = startedSprint(board, "Sprint 1");
        Sprint completed = sprints.complete(active.complete(), List.of(), null).await().indefinitely();

        // Same shape as the start() case: a stale caller re-attempting complete() on a row
        // the database already moved past ACTIVE must be refused, not silently re-stamp
        // completed_at.
        Sprint staleComplete = new Sprint(completed.id(), completed.boardId(), completed.name(),
                SprintStatus.COMPLETED, completed.startedAt(), java.time.Instant.now());
        IllegalSprintTransitionException failure = assertThrows(IllegalSprintTransitionException.class,
                () -> sprints.complete(staleComplete, List.of(), null).await().indefinitely());

        assertTrue(failure.getMessage().contains(completed.id().toString()));
        assertEquals(completed.completedAt(),
                sprints.findById(completed.id()).await().indefinitely().orElseThrow().completedAt(),
                "a refused transition must not re-stamp completed_at");
    }

    @Test
    void theStatusColumnRefusesAStatusTheDomainDoesNotDefine() {
        Board board = newBoard(newWorkspace());

        Throwable failure = assertThrows(Throwable.class, () -> pool.preparedQuery("""
                        insert into sprint (id, board_id, name, status)
                        values ($1, $2, $3, 'PAUSED')
                        """)
                .execute(Tuple.of(TimeOrderedId.next(), board.id(), "paused sprint"))
                .await().indefinitely());

        assertTrue(causeChain(failure).contains("check constraint"),
                "the schema must reject a status SprintStatus cannot represent: " + causeChain(failure));
    }
}
