package io.vectis.domain.template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An in-memory, append-only catalog that publishes the way the spike's registry did: a version
 * goes in, with its resolution materialised, only when {@link TemplateResolver#publish} accepts
 * it. Stands in for persistence (VEC-68) in the hierarchy cases.
 */
final class Registry implements TemplateVersion.Catalog {

    static final List<String> BUILT_INS = List.of("base.v1.json", "kanban.v1.json", "scrum.v1.json", "hybrid.v1.json");

    final List<TemplateVersion> versions = new ArrayList<>();
    final Map<String, Map<String, Object>> resolved = new TreeMap<>();

    /** The shipped hierarchy: the four built-ins, stage-gate as a fifth system template, and Acme's organisation and team levels. */
    static Registry shipped() {
        Registry r = new Registry();
        BUILT_INS.forEach(f -> r.require(TemplateResolver.SYSTEM, Fixtures.load(f)));
        r.require(TemplateResolver.SYSTEM, h("stage-gate.v1.json"));
        r.require("acme", h("acme.v1.json"));
        r.require("acme", h("acme-platform.v1.json"));
        return r;
    }

    static Map<String, Object> h(String file) {
        return Fixtures.load("hierarchy/" + file);
    }

    /** Publishes; the violations, empty when the version is in. */
    List<Violation> publish(String owner, Map<String, Object> document) {
        TemplateVersion candidate = TemplateVersion.of(owner, document);
        Resolution r = TemplateResolver.publish(candidate, this);
        if (r.ok()) {
            versions.add(candidate);
            resolved.put(candidate.ref(), r.effective());
        }
        return r.violations();
    }

    private void require(String owner, Map<String, Object> document) {
        List<Violation> v = publish(owner, document);
        if (!v.isEmpty()) {
            throw new IllegalStateException(document.get("template") + " " + v);
        }
    }

    Map<String, Object> res(String ref) {
        return resolved.get(ref);
    }

    TemplateVersion get(String key, long version) {
        return find(TemplateVersion.ref(key, version)).orElseThrow();
    }

    List<TemplateVersion> chain(String key, long version) {
        List<Violation> v = new ArrayList<>();
        List<TemplateVersion> chain = TemplateHierarchy.chain(get(key, version), this, v);
        if (!v.isEmpty()) {
            throw new IllegalStateException(v.toString());
        }
        return chain;
    }

    List<Map<String, Object>> documents(String key, long version) {
        return chain(key, version).stream().map(TemplateVersion::document).toList();
    }

    @Override
    public Optional<TemplateVersion> find(String ref) {
        return versions.stream().filter(x -> x.ref().equals(ref)).findFirst();
    }

    @Override
    public List<TemplateVersion> versions(String key) {
        return versions.stream().filter(x -> x.key().equals(key)).toList();
    }
}
