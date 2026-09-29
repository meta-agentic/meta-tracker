// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * Thrown by {@link BacklogSource#read()} when the source cannot be read as a whole —
 * its location is missing, unreadable or misconfigured. A single bad entry is not a
 * reason to throw; it is reported in {@link BacklogSnapshot#problems()} instead.
 */
public class BacklogSourceException extends RuntimeException {

    public BacklogSourceException(String message) {
        super(message);
    }

    public BacklogSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
