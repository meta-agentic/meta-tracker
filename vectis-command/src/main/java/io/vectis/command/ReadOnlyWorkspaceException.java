// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

/**
 * The workspace is bound to a backlog source, which stays its system of record (ADR-VEC-04 D1):
 * until write-back exists (D4), no client writes to it, and only an import changes it.
 */
public final class ReadOnlyWorkspaceException extends CommandException {

    private final String source;

    ReadOnlyWorkspaceException(String workspace, String source) {
        super("workspace " + workspace + " is read-only until write-back: its items come from the backlog source "
                + source + ", which stays their system of record");
        this.source = source;
    }

    /** The id of the source the workspace is bound to, such as {@code vault}. */
    public String source() {
        return source;
    }
}
