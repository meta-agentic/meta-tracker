package io.vectis.persistence;

import java.util.UUID;

/**
 * Thrown when a write targets an item that is not in the workspace it names: deleted, never
 * created, or in another workspace. An update that matches no row used to report success
 * anyway; now it fails, and its transaction, which would have appended an event for a change
 * that did not happen, rolls back.
 */
public class ItemNotFoundException extends RuntimeException {

    public ItemNotFoundException(UUID workspaceId, UUID itemId) {
        super("item " + itemId + " is not in workspace " + workspaceId);
    }

    public ItemNotFoundException(UUID itemId) {
        super("item " + itemId + " does not exist");
    }
}
