// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.Board;
import io.vectis.domain.Workspace;
import java.util.Map;

/**
 * What provisioning wrote: the workspace, the template version it is pinned to, its starting
 * delta ({@code {}} when none was given) and its board, whose columns project the effective states.
 */
public record ProvisionedWorkspace(Workspace workspace, TemplateRef template, Map<String, Object> delta, Board board) {
}
