// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

/**
 * The item is no longer at the version the command was made against: someone changed or deleted it
 * since. Nothing is written.
 */
public final class StaleVersionException extends CommandException {

    StaleVersionException(String message) {
        super(message);
    }

    static StaleVersionException changed(String item, long expected, long actual) {
        return new StaleVersionException("item " + item + " is at version " + actual + ", not " + expected);
    }

    static StaleVersionException deleted(String item, long expected) {
        return new StaleVersionException("item " + item + " was deleted after version " + expected);
    }
}
