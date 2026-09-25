package io.vectis.persistence;

/**
 * Thrown when a workspace is inserted under a key another workspace already holds.
 * Translated from the {@code workspace.key} unique constraint rather than pre-checked,
 * so two concurrent provisions of the same key cannot both succeed.
 */
public class WorkspaceKeyConflictException extends RuntimeException {

    private final String key;

    public WorkspaceKeyConflictException(String key, Throwable cause) {
        super("workspace key already in use: " + key, cause);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
