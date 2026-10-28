// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.UUID;

/**
 * A template a tenant can see, at its latest published version.
 *
 * @param tenantId the owning tenant; null for a built-in
 * @param latest   its latest published version
 * @param parent   the version its latest version extends; null for a root
 * @param follow   how its children follow it by default, {@code manual} or {@code auto}
 */
public record TemplateSummary(UUID id, String key, UUID tenantId, TemplateRef latest, TemplateRef parent, String follow) {

    public boolean builtIn() {
        return tenantId == null;
    }
}
