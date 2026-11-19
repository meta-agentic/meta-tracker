// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.UUID;

/**
 * A workspace and the template version it is pinned to.
 *
 * @param id     the workspace's id, for the reads that follow; never shown to a client
 * @param source the backlog source the workspace is bound to, such as {@code vault}, or null for a
 *               workspace Vectis owns
 */
public record WorkspaceSummary(UUID id, String key, String name, TemplateRef template, String source) {

    /** Whether a client may not write here: a bound workspace changes only through its source. */
    public boolean readOnly() {
        return source != null;
    }
}
