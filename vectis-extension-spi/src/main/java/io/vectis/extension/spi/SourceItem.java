// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One work item as a {@link BacklogSource} records it, in the source's own
 * vocabulary. Nothing here is a Vectis decision: an importer maps it onto a
 * workspace's columns, types and fields.
 *
 * <p>References to other items ({@code epicKey}, {@code sprintKey}, {@code links})
 * are source keys, not ids, and may dangle — a source may name an item or sprint it
 * does not contain.</p>
 *
 * @param key         the item's identity in the source, e.g. {@code "VEC-42"}; never blank
 * @param title       never blank
 * @param kind        the source's kind, lower-case, e.g. {@code "story"}, {@code "spike"}
 * @param status      the source's status, verbatim, e.g. {@code "IN REVIEW"}; may be
 *                    blank when the source recorded none
 * @param category    the portable meaning of the status; never {@code null}
 * @param epicKey     key of the parent epic; {@code null} when none
 * @param sprintKey   key of the sprint the item belongs to; {@code null} when none
 * @param storyPoints the estimate; {@code null} when unestimated
 * @param priority    the source's priority, verbatim, e.g. {@code "P0"}; {@code null} when none
 * @param labels      never {@code null}
 * @param links       outgoing links by link type (see {@link #DEPENDS_ON}, {@link #RELATES_TO})
 *                    to target keys; never {@code null}
 * @param body        the item's free-text description; never {@code null}, may be empty
 * @param origin      where the item came from inside the source, for provenance,
 *                    e.g. {@code ".vault/vec/raw/VEC-42.md"}; never {@code null}
 */
public record SourceItem(
        String key,
        String title,
        String kind,
        String status,
        StatusCategory category,
        String epicKey,
        String sprintKey,
        Double storyPoints,
        String priority,
        List<String> labels,
        Map<String, List<String>> links,
        String body,
        String origin) {

    /** Link type: this item cannot finish before the target does. */
    public static final String DEPENDS_ON = "depends-on";

    /** Link type: this item is related to the target, without ordering. */
    public static final String RELATES_TO = "relates-to";

    public SourceItem {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(origin, "origin");
        if (key.isBlank()) {
            throw new IllegalArgumentException("item key must not be blank");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("item title must not be blank");
        }
        labels = List.copyOf(Objects.requireNonNullElse(labels, List.of()));
        links = copyLinks(links);
        body = Objects.requireNonNullElse(body, "");
    }

    private static Map<String, List<String>> copyLinks(Map<String, List<String>> links) {
        if (links == null) {
            return Map.of();
        }
        var copy = new HashMap<String, List<String>>();
        links.forEach((type, targets) -> copy.put(
                Objects.requireNonNull(type, "link type"), List.copyOf(Objects.requireNonNull(targets, type))));
        return Map.copyOf(copy);
    }
}
