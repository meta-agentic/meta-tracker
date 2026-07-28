package io.vectis.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An ordered set of columns over a workspace's items.
 *
 * <p>Columns are held as data rather than as an enum or a fixed set of statuses. That is
 * the "workflows are data" principle in the model itself: a team adds, renames or
 * reorders a column at runtime, and no code changes. The workflow engine that decides
 * which transitions between these columns are legal is VEC-15 and is deliberately not
 * modelled here — this type says what the columns <em>are</em>, not what may move
 * between them.
 */
public record Board(UUID id, UUID workspaceId, String name, List<BoardColumn> columns) {

    public Board {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(name, "name");
        columns = List.copyOf(Objects.requireNonNullElse(columns, List.of()));
        if (name.isBlank()) {
            throw new IllegalArgumentException("board name must not be blank");
        }
        long distinctPositions = columns.stream().map(BoardColumn::position).distinct().count();
        if (distinctPositions != columns.size()) {
            throw new IllegalArgumentException("board columns must have distinct positions: " + columns);
        }
    }

    public static Board create(UUID workspaceId, String name, List<BoardColumn> columns) {
        return new Board(TimeOrderedId.next(), workspaceId, name, columns);
    }

    /** Columns in display order. */
    public List<BoardColumn> orderedColumns() {
        return columns.stream().sorted((a, b) -> Integer.compare(a.position(), b.position())).toList();
    }
}
