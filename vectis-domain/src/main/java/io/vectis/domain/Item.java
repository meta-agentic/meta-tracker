package io.vectis.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A unit of work on a board — the thing a card represents.
 *
 * <p>The shape is deliberately half relational and half open. The fields every board
 * operation needs to index, join or order by ({@code boardId}, {@code columnId},
 * {@code rank}, {@code key}) are real columns; everything a team wants to model that
 * Vectis cannot know in advance — estimates, acceptance criteria, custom fields
 * imported from another tracker — lives in {@code fields} and is stored as JSONB.
 *
 * <p>That split is the point. A tracker whose schema is fixed forces teams to bend to
 * it, and a tracker that is entirely schema-less cannot index or order anything
 * efficiently. Keeping the query surface relational and the long tail in a document
 * gives both.
 *
 * <p>{@code rank} is a sparse lexicographic string rather than an integer index, so a
 * card dropped between two others needs one row updated instead of renumbering the
 * whole column — the difference between an O(1) and an O(n) write on every drag.
 *
 * <p>{@code sprintId} is a real column for the same reason as {@code boardId} or
 * {@code columnId}: sprint scope must be indexed and queried, so it cannot live in the
 * open {@code fields} document. {@code null} means the item is in the backlog rather
 * than assigned to any sprint.
 *
 * <p>{@code version} is the persisted revision this representation reflects: 1 when the
 * item is first written, raised by one by every write that changes it, deletion
 * included. Only the persistence layer raises it, in the same transaction as the change
 * and its event, so the {@code with…} methods here carry it over unchanged. It is what a
 * client compares to tell a newer representation from an older one, and what an
 * {@code If-Match} on an item edit names.
 */
public record Item(
        UUID id,
        UUID workspaceId,
        UUID boardId,
        UUID columnId,
        String key,
        String title,
        String rank,
        Map<String, Object> fields,
        UUID sprintId,
        long version) {

    public Item {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(boardId, "boardId");
        Objects.requireNonNull(columnId, "columnId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(rank, "rank");
        fields = Map.copyOf(Objects.requireNonNullElse(fields, Map.of()));
        if (title.isBlank()) {
            throw new IllegalArgumentException("item title must not be blank");
        }
        if (rank.isBlank()) {
            throw new IllegalArgumentException("item rank must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("item version starts at 1: " + version);
        }
    }

    public static Item create(
            UUID workspaceId, UUID boardId, UUID columnId, String key, String title, String rank) {
        return new Item(TimeOrderedId.next(), workspaceId, boardId, columnId, key, title, rank, Map.of(), null, 1);
    }

    /** The same item moved to another column, leaving every other attribute alone. */
    public Item movedTo(UUID targetColumnId, String newRank) {
        return new Item(id, workspaceId, boardId, targetColumnId, key, title, newRank, fields, sprintId, version);
    }

    /** The same item with its title replaced. */
    public Item withTitle(String newTitle) {
        return new Item(id, workspaceId, boardId, columnId, key, newTitle, rank, fields, sprintId, version);
    }

    /** The same item with its open field set replaced. */
    public Item withFields(Map<String, Object> newFields) {
        return new Item(id, workspaceId, boardId, columnId, key, title, rank, newFields, sprintId, version);
    }

    /** The same item assigned to a sprint, or returned to the backlog if {@code sprintId} is null. */
    public Item withSprint(UUID sprintId) {
        return new Item(id, workspaceId, boardId, columnId, key, title, rank, fields, sprintId, version);
    }
}
