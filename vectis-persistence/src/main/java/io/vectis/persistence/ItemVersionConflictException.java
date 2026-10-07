package io.vectis.persistence;

import java.util.UUID;

/**
 * Thrown when an edit names a version of the item that is no longer the stored one: someone
 * else changed it since the caller read it. Nothing is written. This is what an
 * {@code If-Match} on an item edit maps to.
 */
public class ItemVersionConflictException extends RuntimeException {

    private final long expected;
    private final long actual;

    public ItemVersionConflictException(UUID itemId, long expected, long actual) {
        super("item " + itemId + " is at version " + actual + ", not " + expected);
        this.expected = expected;
        this.actual = actual;
    }

    public long expected() {
        return expected;
    }

    public long actual() {
        return actual;
    }
}
