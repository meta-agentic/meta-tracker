// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.UUID;

/**
 * Thrown when a single-item write targets a workspace bound to a backlog source. The source is the
 * workspace's system of record and the workspace a projection of it (ADR-VEC-04 D1), so until
 * write-back exists (D4) only the import changes it. Checked under the workspace row lock; nothing
 * is written.
 */
public class SourceBoundWorkspaceException extends RuntimeException {

    private final String source;

    public SourceBoundWorkspaceException(UUID workspaceId, String source) {
        super("workspace " + workspaceId + " is bound to the backlog source " + source);
        this.source = source;
    }

    /** The id of the source the workspace is bound to, such as {@code vault}. */
    public String source() {
        return source;
    }
}
