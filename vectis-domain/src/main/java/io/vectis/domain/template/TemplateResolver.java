package io.vectis.domain.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a template chain plus a workspace delta into an effective configuration, by the rules of
 * ADR-VEC-03. Pure: no I/O, no clock, no randomness; neither input is modified.
 */
public final class TemplateResolver {

    /**
     * The version of the resolution rules. A resolved document stored with an older value is
     * recomputed, so bump it whenever a rule here changes what a chain resolves to.
     */
    public static final int RESOLVER_VERSION = 1;

    /**
     * The most template versions a chain may hold, root included; the workspace is not counted.
     * Base, methodology, organisation, unit, team (ADR-VEC-03, Q2).
     */
    public static final int MAX_TEMPLATE_LEVELS = 5;

    /** The owner of built-in templates, shipped by a Vectis release. Any other owner is a tenant. */
    public static final String SYSTEM = "system";

    /** The level a workspace delta's violations and provenance are attributed to. */
    public static final String WORKSPACE = "workspace";

    private TemplateResolver() {
    }

    /**
     * Resolves a chain of template documents, root first, each extending the one before, and an
     * optional workspace delta (null for the template alone). The result carries per-path
     * provenance.
     */
    public static Resolution resolve(List<Map<String, Object>> chain, Map<String, Object> workspaceDelta) {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("a chain holds at least its root");
        }
        List<TemplateVersion> versions = chain.stream().map(d -> TemplateVersion.of(SYSTEM, d)).toList();
        List<Violation> v = new ArrayList<>();
        List<TemplateVersion> walked = TemplateHierarchy.chain(versions.getLast(), TemplateVersion.Catalog.of(versions), v);
        if (v.isEmpty() && !walked.equals(versions)) {
            v.add(new Violation("extends", versions.getLast().ref(), "the chain is not root first with each level extending the one before"));
        }
        if (!v.isEmpty()) {
            return new Resolution(Map.of(), v);
        }
        Resolution template = TemplateHierarchy.resolveChain(versions);
        Resolution result = workspaceDelta == null ? template : resolveWorkspace(template.effective(), workspaceDelta);
        v.addAll(template.violations());
        if (workspaceDelta != null) {
            v.addAll(result.violations());
        }
        return new Resolution(result.effective(), TemplateHierarchy.provenance(versions, workspaceDelta), v);
    }

    /**
     * A workspace's effective configuration over its template version's materialised resolution:
     * the read path, a two-document merge whatever the depth of the chain.
     */
    public static Resolution resolveWorkspace(Map<String, Object> templateResolved, Map<String, Object> delta) {
        return TemplateHierarchy.resolveWorkspace(templateResolved, delta);
    }

    /**
     * What a new version must pass to be published, and, when it does, its resolution to
     * materialise. It must be the key's next version, by the key's owner; extend a published
     * version owned by the system or by the same owner; be a root only if built-in; stay within
     * {@link #MAX_TEMPLATE_LEVELS}; respect every ancestor lock; and resolve cleanly on its own.
     */
    public static Resolution publish(TemplateVersion candidate, TemplateVersion.Catalog catalog) {
        List<Violation> v = new ArrayList<>();
        String key = candidate.key();
        String ref = candidate.ref();
        if (!LevelResolver.KEY.matcher(key).matches()) {
            v.add(new Violation("template", ref, "key must match " + LevelResolver.KEY.pattern()));
        }
        Map<String, Object> doc = candidate.document();
        if (!key.equals(doc.get("template"))) {
            v.add(new Violation("template", ref, "the document names template " + TemplateJson.compact(doc.get("template"))
                    + "; it is published as " + ref));
        }
        if (!(doc.get("version") instanceof Long n && n == candidate.version())) {
            v.add(new Violation("version", ref, "the document names version " + TemplateJson.compact(doc.get("version"))
                    + "; it is published as " + ref));
        }
        if (!candidate.extendsWellFormed()) {
            v.add(new Violation("extends", ref, TemplateHierarchy.EXTENDS_SHAPE));
        }
        long latest = catalog.latest(key);
        if (candidate.version() != latest + 1) {
            v.add(new Violation("version", ref, key + " is at version " + latest + "; a new version is " + (latest + 1)
                    + " (published versions are immutable)"));
        }
        catalog.versions(key).stream().filter(x -> !x.owner().equals(candidate.owner())).findFirst()
                .ifPresent(x -> v.add(new Violation("template", ref, "'" + key + "' belongs to " + x.owner())));
        String parent = candidate.parent();
        if (parent == null && candidate.extendsWellFormed() && !candidate.owner().equals(SYSTEM)) {
            v.add(new Violation("extends", ref, "a tenant template extends a built-in or one of its own templates; roots are built-in"));
        }
        catalog.find(parent == null ? "" : parent)
                .filter(p -> !p.owner().equals(SYSTEM) && !p.owner().equals(candidate.owner()))
                .ifPresent(p -> v.add(new Violation("extends", ref, p.ref() + " belongs to another owner")));
        if (!v.isEmpty()) {
            return new Resolution(Map.of(), v);
        }
        List<TemplateVersion> chain = TemplateHierarchy.chain(candidate, catalog, v);
        if (!v.isEmpty()) {
            return new Resolution(Map.of(), v);
        }
        Resolution r = TemplateHierarchy.resolveChain(chain);
        return new Resolution(r.effective(), TemplateHierarchy.provenance(chain, null), r.violations());
    }
}
