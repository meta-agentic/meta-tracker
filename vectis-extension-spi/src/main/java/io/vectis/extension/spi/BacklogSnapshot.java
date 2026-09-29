// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

import java.util.List;
import java.util.Objects;

/**
 * Everything a {@link BacklogSource} holds, read at one moment.
 *
 * <p>A snapshot is complete rather than a delta: an importer can compare it with what
 * it already has and create, update or prune accordingly, and a re-read of an
 * unchanged source yields an equal snapshot.</p>
 *
 * @param keyPrefix the prefix every item key carries, e.g. {@code "VEC"} for
 *                  {@code "VEC-42"}; never blank
 * @param items     never {@code null}
 * @param sprints   never {@code null}
 * @param problems  human-readable notes on every entry the source skipped or degraded,
 *                  so nothing is dropped silently; never {@code null}. A problem that
 *                  starts with {@link #FATAL} means the source stopped early: the snapshot
 *                  is incomplete, see {@link #complete()}
 */
public record BacklogSnapshot(
        String keyPrefix,
        List<SourceItem> items,
        List<SourceSprint> sprints,
        List<String> problems) {

    /** Prefix of a problem after which the source stopped reading. */
    public static final String FATAL = "FATAL: ";

    public BacklogSnapshot {
        Objects.requireNonNull(keyPrefix, "keyPrefix");
        if (keyPrefix.isBlank()) {
            throw new IllegalArgumentException("key prefix must not be blank");
        }
        items = List.copyOf(Objects.requireNonNullElse(items, List.of()));
        sprints = List.copyOf(Objects.requireNonNullElse(sprints, List.of()));
        problems = List.copyOf(Objects.requireNonNullElse(problems, List.of()));
    }

    /**
     * Whether the source read everything it holds. An incomplete snapshot is still worth
     * showing, but an importer must not treat what it lacks as deleted.
     */
    public boolean complete() {
        return problems.stream().noneMatch(problem -> problem.startsWith(FATAL));
    }
}
