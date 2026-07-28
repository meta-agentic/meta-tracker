package io.vectis.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One column of a board.
 *
 * <p>{@code position} is a dense integer used for display order only. Item ordering
 * <em>within</em> a column is carried by {@link Item#rank()}, which is a sparse string
 * so a card can be dropped between two neighbours without renumbering the column.
 */
public record BoardColumn(UUID id, String name, int position) {

    public BoardColumn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (position < 0) {
            throw new IllegalArgumentException("column position must not be negative: " + position);
        }
    }

    public static BoardColumn create(String name, int position) {
        return new BoardColumn(TimeOrderedId.next(), name, position);
    }
}
