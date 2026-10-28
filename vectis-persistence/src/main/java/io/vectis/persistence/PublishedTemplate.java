// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One published, immutable template version as stored.
 *
 * @param templateId the {@code workflow_template} row the version belongs to
 * @param tenantId   the owning tenant; null for a built-in
 * @param parent     the version it extends; null for a root
 * @param document   the version as authored
 * @param resolved   its resolution through the chain, materialised at publish
 * @param cause      why it was published: {@code {"kind": "authored"}} or a propagation
 */
public record PublishedTemplate(
        UUID templateId,
        TemplateRef ref,
        UUID tenantId,
        TemplateRef parent,
        Map<String, Object> document,
        Map<String, Object> resolved,
        int resolverVersion,
        Map<String, Object> cause,
        OffsetDateTime publishedAt) {

    public boolean builtIn() {
        return tenantId == null;
    }
}
