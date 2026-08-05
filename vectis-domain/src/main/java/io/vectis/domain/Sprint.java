package io.vectis.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * A time-boxed scope of items against a board: {@link SprintStatus#FUTURE} until
 * started, then {@link SprintStatus#ACTIVE}, then {@link SprintStatus#COMPLETED}.
 *
 * <p>A board may have at most one active sprint at a time. Every other space in the
 * estate already runs one active sprint per its cadence, and Jira makes multi-active an
 * opt-in board setting precisely because it is the exception, not the default — so
 * single-active is the simpler invariant and the one that matches actual usage. It is
 * enforced server-side, at the persistence layer, not here.
 */
public record Sprint(UUID id, UUID boardId, String name, SprintStatus status, Instant startedAt,
        Instant completedAt) {

    public Sprint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(boardId, "boardId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        if (name.isBlank()) {
            throw new IllegalArgumentException("sprint name must not be blank");
        }
        if (status != SprintStatus.FUTURE && startedAt == null) {
            throw new IllegalArgumentException("startedAt is required once a sprint has left FUTURE: " + status);
        }
        if (status == SprintStatus.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("completedAt is required for a COMPLETED sprint");
        }
    }

    public static Sprint create(UUID boardId, String name) {
        return new Sprint(TimeOrderedId.next(), boardId, name, SprintStatus.FUTURE, null, null);
    }

    /** Starts the sprint. Legal only from {@link SprintStatus#FUTURE}. */
    public Sprint start() {
        if (status != SprintStatus.FUTURE) {
            throw new IllegalStateException("cannot start sprint " + id + " from state " + status);
        }
        return new Sprint(id, boardId, name, SprintStatus.ACTIVE, now(), completedAt);
    }

    /** Completes the sprint. Legal only from {@link SprintStatus#ACTIVE}. */
    public Sprint complete() {
        if (status != SprintStatus.ACTIVE) {
            throw new IllegalStateException("cannot complete sprint " + id + " from state " + status);
        }
        return new Sprint(id, boardId, name, SprintStatus.COMPLETED, startedAt, now());
    }

    /**
     * Truncated to microseconds, PostgreSQL's {@code timestamptz} precision. Without
     * this, a sprint's equality to its own persisted-and-reloaded form is platform-
     * dependent: {@code Instant.now()} carries nanoseconds on some JVMs (Linux) and only
     * microseconds on others (macOS), so a round trip through storage silently drops
     * precision on the former and not the latter.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
