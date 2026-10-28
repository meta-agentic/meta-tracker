// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.template.Resolution;
import io.vectis.domain.template.TemplateResolver;
import io.vectis.persistence.ConfigurationViolationException;
import io.vectis.persistence.PublishedTemplate;
import io.vectis.persistence.TemplateNotFoundException;
import io.vectis.persistence.TemplateRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/** Reads a template version's chain through {@link TemplateRepository} and resolves it. */
@ApplicationScoped
public class TemplateChains {

    private static final Logger LOG = Logger.getLogger(TemplateChains.class);

    private final TemplateRepository templates;

    public TemplateChains(TemplateRepository templates) {
        this.templates = templates;
    }

    /** The chain ending at {@code leaf}, root first. */
    public Uni<List<PublishedTemplate>> chain(PublishedTemplate leaf) {
        return up(leaf, new ArrayList<>(List.of(leaf)));
    }

    private Uni<List<PublishedTemplate>> up(PublishedTemplate x, List<PublishedTemplate> found) {
        if (x.parent() == null) {
            return Uni.createFrom().item(List.copyOf(found.reversed()));
        }
        if (found.size() >= TemplateResolver.MAX_TEMPLATE_LEVELS) {
            throw new IllegalStateException("template chain of " + found.getFirst().ref() + " is longer than "
                    + TemplateResolver.MAX_TEMPLATE_LEVELS + " levels");
        }
        return templates.findVersion(x.tenantId(), x.parent()).chain(parent -> {
            PublishedTemplate p = parent.orElseThrow(() -> new TemplateNotFoundException(x.parent()));
            found.add(p);
            return up(p, found);
        });
    }

    /**
     * The effective configuration of {@code chain}'s leaf under {@code delta} (null: the template
     * alone). It reads the materialised resolution, a two-document merge, unless that was made by
     * older resolution rules or the per-path provenance is wanted: then the chain is resolved
     * again, and the stale case is logged. The request never fails for staleness.
     *
     * @throws ConfigurationViolationException when what is stored does not resolve
     */
    public Resolution resolve(List<PublishedTemplate> chain, Map<String, Object> delta, boolean provenance) {
        PublishedTemplate leaf = chain.getLast();
        boolean stale = stale(leaf);
        Resolution resolution;
        if (stale || provenance) {
            if (stale) {
                LOG.warnf("%s was resolved by resolver version %d, current is %d; recomputing on read",
                        leaf.ref(), leaf.resolverVersion(), TemplateResolver.RESOLVER_VERSION);
            }
            resolution = TemplateResolver.resolve(chain.stream().map(PublishedTemplate::document).toList(), delta);
        } else {
            resolution = delta == null
                    ? new Resolution(leaf.resolved(), Map.of(), List.of())
                    : TemplateResolver.resolveWorkspace(leaf.resolved(), delta);
        }
        if (!resolution.ok()) {
            throw new ConfigurationViolationException(resolution.violations());
        }
        return resolution;
    }

    public static boolean stale(PublishedTemplate version) {
        return version.resolverVersion() < TemplateResolver.RESOLVER_VERSION;
    }
}
