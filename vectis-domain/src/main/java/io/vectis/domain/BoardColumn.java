package io.vectis.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One column of a board: the projection of one workflow state, addressed by the state's
 * {@code key} (ADR-VEC-03). A rename changes {@code name} and keeps the key, so nothing that
 * refers to the column by key breaks.
 *
 * <p>{@code position} is a dense integer used for display order only. Item ordering
 * <em>within</em> a column is carried by {@link Item#rank()}, which is a sparse string
 * so a card can be dropped between two neighbours without renumbering the column.
 * {@code onBoard} is false for a state the board does not render, such as a discontinued
 * end; the column exists either way, so its items keep their place.
 */
public record BoardColumn(UUID id, String key, String name, int position, boolean onBoard) {

    /** The state key pattern of the template schema. */
    public static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9-]{0,31}");

    public BoardColumn {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("column key must match " + KEY.pattern() + ": " + key);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (position < 0) {
            throw new IllegalArgumentException("column position must not be negative: " + position);
        }
    }

    public static BoardColumn create(String key, String name, int position, boolean onBoard) {
        return new BoardColumn(TimeOrderedId.next(), key, name, position, onBoard);
    }

    public static BoardColumn create(String key, String name, int position) {
        return create(key, name, position, true);
    }
}
