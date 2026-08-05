package io.vectis.persistence;

import java.util.UUID;

/**
 * Thrown when starting a sprint would leave its board with more than one active
 * sprint. Translated from the {@code sprint_board_single_active_idx} constraint
 * violation rather than pre-checked, so a race between two concurrent starts cannot
 * leave both believing they won.
 */
public class ActiveSprintConflictException extends RuntimeException {

    public ActiveSprintConflictException(UUID boardId, Throwable cause) {
        super("board " + boardId + " already has an active sprint", cause);
    }
}
