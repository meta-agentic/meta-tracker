package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.Sprint;
import io.vectis.domain.SprintStatus;
import io.vectis.domain.Workspace;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Exercises sprint lifecycle persistence and sprint/backlog item scope against a real
 * PostgreSQL, mirroring {@link PersistenceRoundTripTest}: every assertion here concerns
 * behaviour a mock could not report, chiefly that the single-active-sprint invariant
 * (VEC-43) actually holds under a real constraint violation.
 */
@QuarkusTest
class SprintPersistenceTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;
    @Inject SprintRepository sprints;

    private Workspace newWorkspace() {
        String suffix = Integer.toString(KEY_SEQ.incrementAndGet());
        return workspaces.insert(Workspace.create("SP" + suffix, "Workspace " + suffix))
                .await().indefinitely();
    }

    private Board newBoard(Workspace ws) {
        Board board = Board.create(ws.id(), "Delivery", List.of(
                BoardColumn.create("Backlog", 0),
                BoardColumn.create("In Progress", 1),
                BoardColumn.create("Done", 2)));
        return boards.insert(board).await().indefinitely();
    }

    private final AtomicInteger itemKeySeq = new AtomicInteger();

    private Item newItem(Workspace ws, Board board) {
        UUID backlog = board.orderedColumns().getFirst().id();
        String key = ws.key() + "-" + itemKeySeq.incrementAndGet();
        return items.insert(Item.create(ws.id(), board.id(), backlog, key, "An item", "m"))
                .await().indefinitely();
    }

    @Test
    void sprintRoundTrips() {
        Board board = newBoard(newWorkspace());
        Sprint saved = sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely();

        Sprint loaded = sprints.findById(saved.id()).await().indefinitely().orElseThrow();

        assertEquals(saved, loaded);
        assertEquals(SprintStatus.FUTURE, loaded.status());
    }

    @Test
    void startAndCompletePersistTheirTimestamps() {
        Board board = newBoard(newWorkspace());
        Sprint future = sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely();

        Sprint active = sprints.start(future.start()).await().indefinitely();
        Sprint activeLoaded = sprints.findById(active.id()).await().indefinitely().orElseThrow();
        assertEquals(SprintStatus.ACTIVE, activeLoaded.status());
        assertEquals(active.startedAt(), activeLoaded.startedAt());

        Sprint completed = sprints.complete(active.complete(), List.of(), null).await().indefinitely();
        Sprint completedLoaded = sprints.findById(completed.id()).await().indefinitely().orElseThrow();
        assertEquals(SprintStatus.COMPLETED, completedLoaded.status());
        assertEquals(completed.completedAt(), completedLoaded.completedAt());
    }

    @Test
    void aBoardCannotHaveTwoActiveSprintsAtOnce() {
        Board board = newBoard(newWorkspace());
        Sprint first = sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely();
        Sprint second = sprints.insert(Sprint.create(board.id(), "Sprint 2")).await().indefinitely();

        sprints.start(first.start()).await().indefinitely();

        assertThrows(ActiveSprintConflictException.class,
                () -> sprints.start(second.start()).await().indefinitely());
    }

    @Test
    void anotherBoardMayStillHaveItsOwnActiveSprint() {
        Workspace ws = newWorkspace();
        Board boardA = newBoard(ws);
        Board boardB = newBoard(ws);
        Sprint a = sprints.insert(Sprint.create(boardA.id(), "Sprint A")).await().indefinitely();
        Sprint b = sprints.insert(Sprint.create(boardB.id(), "Sprint B")).await().indefinitely();

        sprints.start(a.start()).await().indefinitely();
        Sprint startedB = sprints.start(b.start()).await().indefinitely();

        assertEquals(SprintStatus.ACTIVE, startedB.status());
    }

    @Test
    void itemsCanBeAssignedToAndRemovedFromASprint() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Sprint sprint = sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely();
        Item item = newItem(ws, board);

        Item assigned = items.moveToSprint(item, sprint.id()).await().indefinitely();
        assertEquals(sprint.id(), items.findById(item.id()).await().indefinitely().orElseThrow().sprintId());
        assertEquals(sprint.id(), assigned.sprintId());

        Item removed = items.moveToSprint(assigned, null).await().indefinitely();
        assertNull(items.findById(item.id()).await().indefinitely().orElseThrow().sprintId());
        assertNull(removed.sprintId());
    }

    @Test
    void backlogScopeAndSprintScopeAreDistinct() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Sprint sprint = sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely();
        Item inBacklog = newItem(ws, board);
        Item inSprint = items.moveToSprint(newItem(ws, board), sprint.id()).await().indefinitely();

        List<Item> backlog = items.findByBoardScope(board.id(), null).await().indefinitely();
        List<Item> sprintScope = items.findByBoardScope(board.id(), sprint.id()).await().indefinitely();

        assertEquals(List.of(inBacklog.id()), backlog.stream().map(Item::id).toList());
        assertEquals(List.of(inSprint.id()), sprintScope.stream().map(Item::id).toList());
    }

    @Test
    void completingASprintMovesUnfinishedItemsToTheNextSprint() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Sprint active = sprints.start(
                        sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely().start())
                .await().indefinitely();
        Sprint next = sprints.insert(Sprint.create(board.id(), "Sprint 2")).await().indefinitely();
        Item unfinished = items.moveToSprint(newItem(ws, board), active.id()).await().indefinitely();

        sprints.complete(active.complete(), List.of(unfinished.id()), next.id()).await().indefinitely();

        Item moved = items.findById(unfinished.id()).await().indefinitely().orElseThrow();
        assertEquals(next.id(), moved.sprintId());
    }

    @Test
    void completingASprintCanReturnUnfinishedItemsToTheBacklog() {
        Workspace ws = newWorkspace();
        Board board = newBoard(ws);
        Sprint active = sprints.start(
                        sprints.insert(Sprint.create(board.id(), "Sprint 1")).await().indefinitely().start())
                .await().indefinitely();
        Item unfinished = items.moveToSprint(newItem(ws, board), active.id()).await().indefinitely();

        sprints.complete(active.complete(), List.of(unfinished.id()), null).await().indefinitely();

        Item moved = items.findById(unfinished.id()).await().indefinitely().orElseThrow();
        assertNull(moved.sprintId());
    }

    @Test
    void listByBoardReturnsEverySprintOnTheBoard() {
        Board board = newBoard(newWorkspace());
        Sprint a = sprints.insert(Sprint.create(board.id(), "Sprint A")).await().indefinitely();
        Sprint b = sprints.insert(Sprint.create(board.id(), "Sprint B")).await().indefinitely();

        List<UUID> ids = sprints.listByBoard(board.id()).await().indefinitely().stream().map(Sprint::id).toList();

        assertTrue(ids.containsAll(List.of(a.id(), b.id())));
    }
}
