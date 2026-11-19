// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

/**
 * A command that changes an existing item names no version: every change states the version it
 * was made against, so that none silently overwrites another.
 */
public final class VersionRequiredException extends CommandException {

    VersionRequiredException(String item) {
        super("a change to item " + item + " names the version it is made against");
    }
}
