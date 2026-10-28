// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.UUID;

/**
 * A workspace and the template version it is pinned to.
 *
 * @param id the workspace's id, for the reads that follow; never shown to a client
 */
public record WorkspaceSummary(UUID id, String key, String name, TemplateRef template) {
}
