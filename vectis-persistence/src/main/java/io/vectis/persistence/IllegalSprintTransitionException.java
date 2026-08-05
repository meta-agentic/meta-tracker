package io.vectis.persistence;

import io.vectis.domain.SprintStatus;
import java.util.UUID;

/**
 * Thrown when {@link SprintRepository#start} or {@link SprintRepository#complete} targets
 * a row that is no longer in the state the caller's in-memory {@link io.vectis.domain.Sprint}
 * assumed. {@code Sprint.start()}/{@code Sprint.complete()} only validate the caller's own
 * copy, not the database's current row — without this guard a stale or concurrently-modified
 * sprint could be silently pushed through an illegal transition (e.g. re-activating an
 * already-completed sprint), since a blind {@code UPDATE} reports success either way.
 */
public class IllegalSprintTransitionException extends RuntimeException {

    public IllegalSprintTransitionException(UUID sprintId, SprintStatus expected, SprintStatus attempted) {
        super("sprint " + sprintId + " is not " + expected
                + " (concurrently modified, or already " + attempted + "?) — refusing transition to " + attempted);
    }
}
