package io.vectis.server.api;

import io.vectis.domain.Board;
import io.vectis.domain.BoardColumn;
import io.vectis.domain.Item;
import io.vectis.domain.ProjectTemplate;
import io.vectis.domain.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The response shapes of the product API, held apart from the domain records so that a
 * change to an aggregate is not silently a change to the wire. Field names are the
 * contract {@code web/src/api/wire.ts} decodes.
 */
final class WireViews {

    private WireViews() {
    }

    record WorkspaceView(UUID id, String key, String name) {
        static WorkspaceView of(Workspace workspace) {
            return new WorkspaceView(workspace.id(), workspace.key(), workspace.name());
        }
    }

    /** {@code position} is the column's dense display index. */
    record ColumnView(UUID id, String name, int position) {
        static ColumnView of(BoardColumn column) {
            return new ColumnView(column.id(), column.name(), column.position());
        }
    }

    record BoardView(UUID id, UUID workspaceId, String name, List<ColumnView> columns) {
        static BoardView of(Board board) {
            return new BoardView(board.id(), board.workspaceId(), board.name(),
                    board.orderedColumns().stream().map(ColumnView::of).toList());
        }
    }

    /**
     * {@code rank} is the sparse ordering key within a column; there is no dense
     * position. {@code fields} is passed through untouched, and {@code sprintId} is
     * always present, null for a backlog item.
     */
    record ItemView(
            UUID id,
            UUID workspaceId,
            UUID boardId,
            UUID columnId,
            String key,
            String title,
            String rank,
            Map<String, Object> fields,
            UUID sprintId) {
        static ItemView of(Item item) {
            return new ItemView(item.id(), item.workspaceId(), item.boardId(), item.columnId(),
                    item.key(), item.title(), item.rank(), item.fields(), item.sprintId());
        }
    }

    record TemplateView(String id, String name, List<String> columns, List<String> issueTypes) {
        static TemplateView of(ProjectTemplate template) {
            return new TemplateView(template.id(), template.displayName(),
                    template.columnNames(), template.issueTypes());
        }
    }

    record ProvisionedWorkspace(WorkspaceView workspace, List<BoardView> boards) {
    }
}
