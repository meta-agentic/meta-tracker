package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.ProjectTemplate;
import io.vectis.domain.Workspace;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Workspace provisioning and the workspace-wide reads the board client hydrates from.
 * Real PostgreSQL for the same reason as the rest of this module: atomicity and the
 * unique-key translation are properties of the database, not of the Java around it.
 */
@QuarkusTest
class ProvisioningPersistenceTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject WorkspaceProvisioningRepository provisioning;
    @Inject WorkspaceRepository workspaces;
    @Inject BoardRepository boards;
    @Inject ItemRepository items;

    private static String freshKey() {
        return "PV" + KEY_SEQ.incrementAndGet();
    }

    @Test
    void provisionPersistsTheWorkspaceAndItsTemplateBoard() {
        Workspace ws = Workspace.create(freshKey(), "Provisioned");
        Board board = ProjectTemplate.SCRUM.instantiate(ws.id());

        provisioning.provision(ws, List.of(board)).await().indefinitely();

        assertEquals(ws, workspaces.findByKey(ws.key()).await().indefinitely().orElseThrow());
        List<Board> stored = boards.findByWorkspace(ws.id()).await().indefinitely();
        assertEquals(1, stored.size());
        assertEquals(board.id(), stored.get(0).id());
        assertEquals(ProjectTemplate.SCRUM.columnNames(),
                stored.get(0).orderedColumns().stream().map(BoardColumn::name).toList());
    }

    @Test
    void aDuplicateKeyIsAConflictAndLeavesNothingBehind() {
        String key = freshKey();
        Workspace first = Workspace.create(key, "First");
        provisioning.provision(first, List.of(ProjectTemplate.KANBAN.instantiate(first.id())))
                .await().indefinitely();

        Workspace second = Workspace.create(key, "Second");
        Board secondBoard = ProjectTemplate.KANBAN.instantiate(second.id());
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> provisioning.provision(second, List.of(secondBoard)).await().indefinitely());

        assertInstanceOf(WorkspaceKeyConflictException.class, thrown);
        assertTrue(boards.findById(secondBoard.id()).await().indefinitely().isEmpty());
    }

    @Test
    void aFailingBoardRollsTheWorkspaceBack() {
        Workspace ws = Workspace.create(freshKey(), "Rolled back");
        Board good = ProjectTemplate.KANBAN.instantiate(ws.id());
        // Same board id twice: the second insert violates the primary key mid-transaction.
        Board clash = new Board(good.id(), ws.id(), "Clash", List.of(BoardColumn.create("Only", 0)));

        assertThrows(RuntimeException.class,
                () -> provisioning.provision(ws, List.of(good, clash)).await().indefinitely());

        assertTrue(workspaces.findByKey(ws.key()).await().indefinitely().isEmpty());
    }

    @Test
    void findByWorkspaceReturnsOnlyThatWorkspacesBoardsWithColumnsInOrder() {
        Workspace mine = Workspace.create(freshKey(), "Mine");
        Workspace other = Workspace.create(freshKey(), "Other");
        Board a = ProjectTemplate.KANBAN.instantiate(mine.id());
        Board b = ProjectTemplate.SCRUM.instantiate(mine.id());
        provisioning.provision(mine, List.of(a, b)).await().indefinitely();
        provisioning.provision(other, List.of(ProjectTemplate.KANBAN.instantiate(other.id())))
                .await().indefinitely();

        List<Board> stored = boards.findByWorkspace(mine.id()).await().indefinitely();

        assertEquals(List.of(a.id(), b.id()), stored.stream().map(Board::id).toList());
        assertEquals(List.of(0, 1, 2, 3, 4),
                stored.get(1).columns().stream().map(BoardColumn::position).toList());
    }

    @Test
    void findByWorkspaceOnAnEmptyWorkspaceIsEmpty() {
        Workspace ws = workspaces.insert(Workspace.create(freshKey(), "Empty")).await().indefinitely();

        assertTrue(boards.findByWorkspace(ws.id()).await().indefinitely().isEmpty());
        assertTrue(items.findByWorkspace(ws.id()).await().indefinitely().isEmpty());
    }

    @Test
    void itemsByWorkspaceComeBackWithRankAndFieldsIntact() {
        Workspace ws = Workspace.create(freshKey(), "Items");
        Board board = ProjectTemplate.KANBAN.instantiate(ws.id());
        provisioning.provision(ws, List.of(board)).await().indefinitely();
        List<BoardColumn> cols = board.orderedColumns();

        Item epic = Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-1", "Epic", "m")
                .withFields(Map.of("type", "epic"));
        Item late = Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-2", "Late", "t")
                .withFields(Map.of("type", "task", "parentId", epic.id().toString(), "storyPoints", 3));
        Item early = Item.create(ws.id(), board.id(), cols.get(0).id(), ws.key() + "-3", "Early", "c");
        items.insertAll(List.of(epic, late, early)).await().indefinitely();

        List<Item> stored = items.findByWorkspace(ws.id()).await().indefinitely();

        assertEquals(List.of("c", "m", "t"), stored.stream().map(Item::rank).toList());
        Item storedLate = stored.get(2);
        assertEquals(epic.id().toString(), storedLate.fields().get("parentId"));
        assertEquals(3, ((Number) storedLate.fields().get("storyPoints")).intValue());
    }
}
