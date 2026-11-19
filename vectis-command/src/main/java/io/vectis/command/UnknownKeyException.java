// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

/** The command names a workspace or an item by a key that names nothing. */
public final class UnknownKeyException extends CommandException {

    UnknownKeyException(String message) {
        super(message);
    }

    static UnknownKeyException workspace(String workspace) {
        return new UnknownKeyException("workspace " + workspace + " does not exist");
    }

    static UnknownKeyException item(String workspace, String item) {
        return new UnknownKeyException("workspace " + workspace + " has no item " + item);
    }
}
