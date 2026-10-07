package io.vectis.persistence;

import java.util.UUID;

/**
 * Thrown when a write names a workspace that does not exist. The write path locks the
 * workspace row before anything else, so this is the first thing such a write learns, and
 * nothing it would have written is left behind.
 */
public class WorkspaceNotFoundException extends RuntimeException {

    public WorkspaceNotFoundException(UUID workspaceId) {
        super("workspace " + workspaceId + " does not exist");
    }
}
