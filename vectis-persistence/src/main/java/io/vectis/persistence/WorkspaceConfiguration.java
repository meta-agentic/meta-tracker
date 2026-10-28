// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.Map;
import java.util.UUID;

/**
 * What a workspace row says about its configuration: the template version it is pinned to, how
 * it follows that template, and the delta it carries over it, as stored.
 *
 * @param templateTenantId the owner of the pinned template; null for a built-in
 * @param latestVersion    the latest published version of the pinned template's key
 * @param revision         the workspace's configuration revision, bumped by every change to it
 */
public record WorkspaceConfiguration(
        UUID workspaceId,
        TemplateRef template,
        UUID templateTenantId,
        int latestVersion,
        String follow,
        Map<String, Object> delta,
        long revision) {
}
