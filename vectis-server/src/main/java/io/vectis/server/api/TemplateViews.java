// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.domain.template.TemplateResolver;
import io.vectis.persistence.PublishedTemplate;
import io.vectis.persistence.TemplateRef;
import java.util.UUID;

/** The JSON shapes the template and configuration resources share. */
final class TemplateViews {

    private TemplateViews() {
    }

    /** {@code system} for a built-in, the tenant otherwise. */
    static String owner(UUID tenantId) {
        return tenantId == null ? TemplateResolver.SYSTEM : tenantId.toString();
    }

    /** A template version named in a response. */
    record Ref(String key, int version) {
        static Ref of(TemplateRef ref) {
            return ref == null ? null : new Ref(ref.key(), ref.version());
        }
    }

    /** One level of a chain, root first. */
    record ChainLink(String key, int version, String owner, String name) {
        static ChainLink of(PublishedTemplate level) {
            return new ChainLink(level.ref().key(), level.ref().version(), TemplateViews.owner(level.tenantId()),
                    level.document().get("name") instanceof String n ? n : null);
        }
    }
}
