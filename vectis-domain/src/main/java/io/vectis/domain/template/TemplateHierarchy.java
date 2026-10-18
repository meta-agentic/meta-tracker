package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.compact;
import static io.vectis.domain.template.TemplateJson.deepCopy;
import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Templates that extend templates, down to the workspace (VEC-66, ADR-VEC-03).
 *
 * <pre>
 * resolved(root)  = resolve(root, {})
 * resolved(T@n)   = resolve(strip(resolved(parent of T@n)), delta(T@n))
 * effective(ws)   = resolve(strip(resolved(T@n)), ws.delta)
 * </pre>
 *
 * {@code strip} drops the parent's placement anchors: anchors are level-local, resolved against
 * the parent's order and plain order for the level below. Also here: locks, which paths no
 * descendant may change, and provenance.
 */
final class TemplateHierarchy {

    private TemplateHierarchy() {
    }

    static final String EXTENDS_SHAPE = "must be an object with a template key and a version from 1";

    /** Document members that say what a version is, not what it configures; never part of a delta. */
    static final List<String> IDENTITY = List.of("template", "version", "name", "description", "extends");

    /**
     * The chain from the root down to {@code leaf}, root first. Refused when a parent is missing,
     * the chain loops or it holds more than {@link TemplateResolver#MAX_TEMPLATE_LEVELS} versions.
     */
    static List<TemplateVersion> chain(TemplateVersion leaf, TemplateVersion.Catalog catalog, List<Violation> v) {
        List<TemplateVersion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TemplateVersion x = leaf; x != null; ) {
            if (!seen.add(x.ref())) {
                v.add(new Violation("extends", x.ref(), "the chain loops back to " + x.ref()));
                break;
            }
            out.addFirst(x);
            if (out.size() > TemplateResolver.MAX_TEMPLATE_LEVELS) {
                v.add(new Violation("extends", leaf.ref(), "more than " + TemplateResolver.MAX_TEMPLATE_LEVELS
                        + " template levels, root included"));
                break;
            }
            if (!x.extendsWellFormed()) {
                v.add(new Violation("extends", x.ref(), EXTENDS_SHAPE));
                break;
            }
            String p = x.parent();
            TemplateVersion child = x;
            x = p == null ? null : catalog.find(p).orElse(null);
            if (p != null && x == null) {
                v.add(new Violation("extends", child.ref(), p + " is not a published template version"));
            }
        }
        return out;
    }

    /** Resolve a whole chain, root first, level by level: what publishing materialises. */
    static Resolution resolveChain(List<TemplateVersion> chain) {
        List<Violation> v = new ArrayList<>();
        TemplateVersion root = chain.getFirst();
        Map<String, Object> rootDoc = obj(deepCopy(root.document()));
        if (rootDoc.remove("deliveryGates") != null) {
            v.add(new Violation("deliveryGates", root.ref(), "set by resolution, never authored"));
        }
        Resolution r = LevelResolver.resolve(rootDoc, Map.of());
        r.violations().forEach(x -> v.add(x.at(root.ref())));
        Map<String, Object> doc = r.effective();
        freezeDeliveryGates(doc);
        for (TemplateVersion x : chain.subList(1, chain.size())) {
            Resolution d = derive(doc, x.document(), x.ref());
            v.addAll(d.violations());
            doc = d.effective();
        }
        return new Resolution(doc, v);
    }

    /** Resolve one derived level over its parent's resolved document. */
    static Resolution derive(Map<String, Object> parentResolved, Map<String, Object> document, String level) {
        Map<String, Object> parent = strip(parentResolved);
        Map<String, Object> delta = sections(document);
        List<Violation> v = new ArrayList<>(lockViolations(strings(parent.get("locks")), delta));
        Set<String> locks = new TreeSet<>(strings(parent.get("locks")));
        locks.addAll(strings(document.get("locks")));
        // Which gates count for gatedDelivery (the validator counts the gates locked in the document
        // it validates):
        //  - once the rule is binding (an ancestor locked settings.gatedDelivery), only the gates
        //    frozen at that level, carried down as deliveryGates; no level can vouch for a gate that
        //    it or its descendants then use;
        //  - while the rule is inherited but not binding, only the parent's locks: a level cannot
        //    vouch for a gate it added itself;
        //  - at a level that turns the rule on, its own locks count.
        // In the first two cases this level's own locks join after validation.
        List<String> frozen = parentResolved.get("deliveryGates") instanceof List<?> ? strings(parentResolved.get("deliveryGates")) : null;
        boolean inherited = Boolean.TRUE.equals(obj(parent.get("settings")).get("gatedDelivery"));
        if (frozen != null) {
            parent.put("locks", frozen);
        } else if (!inherited && !locks.isEmpty()) {
            parent.put("locks", new ArrayList<>(locks));
        }
        Resolution res = LevelResolver.resolve(parent, delta);
        v.addAll(res.violations());
        Map<String, Object> eff = res.effective();
        if ((frozen != null || inherited) && !locks.isEmpty()) {
            eff.put("locks", new ArrayList<>(locks));
            TemplateValidator.locks(eff, v);
        }
        if (frozen == null) {
            freezeDeliveryGates(eff);
        }
        v.addAll(lockedValues(strings(parentResolved.get("locks")), parentResolved, eff));
        IDENTITY.forEach(eff::remove);
        Map<String, Object> out = new LinkedHashMap<>();
        IDENTITY.stream().filter(document::containsKey).forEach(m -> out.put(m, deepCopy(document.get(m))));
        out.putAll(eff);
        if (!(document.get("name") instanceof String n) || n.isBlank()) {
            v.add(Violation.of("name", "a template version needs its own name"));
        }
        v.replaceAll(x -> x.at(level));
        return new Resolution(out, v);
    }

    /** A workspace's effective configuration over its materialised template version. */
    static Resolution resolveWorkspace(Map<String, Object> templateResolved, Map<String, Object> delta) {
        List<String> locks = strings(templateResolved.get("locks"));
        List<Violation> v = new ArrayList<>(lockViolations(locks, delta));
        Resolution res = LevelResolver.resolve(forValidation(templateResolved), delta);
        v.addAll(res.violations());
        // Validated against the frozen gates; the effective document shows the full locks again.
        if (templateResolved.containsKey("locks")) {
            res.effective().put("locks", deepCopy(templateResolved.get("locks")));
        }
        v.addAll(lockedValues(locks, templateResolved, res.effective()));
        v.replaceAll(x -> x.at(TemplateResolver.WORKSPACE));
        return new Resolution(res.effective(), v);
    }

    /**
     * The level where gatedDelivery becomes binding (on, and settings.gatedDelivery locked) freezes
     * the gates that govern it: the gate locks it holds, {@code states.k.gate} or a whole
     * {@code states.k}. Recorded as deliveryGates, which no document may author, and inherited
     * unchanged by every level and workspace below.
     */
    static void freezeDeliveryGates(Map<String, Object> eff) {
        List<String> locks = strings(eff.get("locks"));
        if (Boolean.TRUE.equals(obj(eff.get("settings")).get("gatedDelivery")) && locks.contains("settings.gatedDelivery")) {
            eff.put("deliveryGates", locks.stream()
                    .filter(l -> l.startsWith("states.") && (l.endsWith(".gate") || l.split("\\.").length == 2)).toList());
        }
    }

    /** The resolved version as a workspace validates against it: under a binding rule, only the frozen gates count. */
    static Map<String, Object> forValidation(Map<String, Object> resolved) {
        Map<String, Object> doc = strip(resolved);
        if (resolved.get("deliveryGates") instanceof List<?>) {
            doc.put("locks", strings(resolved.get("deliveryGates")));
        }
        return doc;
    }

    /** The parent's resolved document as the next level sees it: a deep copy without placement anchors. */
    static Map<String, Object> strip(Map<String, Object> resolved) {
        Map<String, Object> doc = obj(deepCopy(resolved));
        obj(doc.get("states")).values().forEach(s -> {
            obj(s).remove("after");
            obj(s).remove("before");
        });
        return doc;
    }

    /** A derived document's delta: everything but its identity and its own locks. */
    static Map<String, Object> sections(Map<String, Object> document) {
        Map<String, Object> d = new LinkedHashMap<>();
        document.forEach((k, x) -> {
            if (!IDENTITY.contains(k) && !k.equals("locks")) {
                d.put(k, deepCopy(x));
            }
        });
        return d;
    }

    // ---- locks ----------------------------------------------------------

    /** A path a delta touches. It hits a lock on the same path, above it, or below it. */
    record Touch(String path) {
        boolean hits(String lock) {
            return path.equals(lock) || path.startsWith(lock + ".") || lock.startsWith(path + ".");
        }
    }

    /** Every path a delta touches, at field level: "states.qa.wipLimit", "states.bug" for a tombstone, "stateOrder". */
    static List<Touch> touches(Map<String, Object> delta) {
        List<Touch> out = new ArrayList<>();
        for (String s : new TreeSet<>(delta.keySet())) {
            if (!(delta.get(s) instanceof Map<?, ?> section)) {
                out.add(new Touch(s));
                continue;
            }
            obj(section).forEach((k, el) -> {
                if (!LevelResolver.KEYED.contains(s) || !(el instanceof Map<?, ?> m)) {
                    out.add(new Touch(s + "." + k));
                } else {
                    obj(m).keySet().forEach(f -> out.add(new Touch(s + "." + k + "." + f)));
                }
            });
        }
        return out;
    }

    /**
     * The first lock check, on the delta: it may not touch a locked path, anything under it, or an
     * element containing it (a tombstone of "states.gate-1" touches "states.gate-1.gate").
     * Overriding another field of the same element is allowed.
     */
    static List<Violation> lockViolations(List<String> locks, Map<String, Object> delta) {
        List<Violation> v = new ArrayList<>();
        for (Touch t : touches(delta)) {
            locks.stream().filter(t::hits).findFirst()
                    .ifPresent(l -> v.add(Violation.of(t.path(), "locked by an ancestor template ('" + l + "')")));
        }
        return v;
    }

    /**
     * The second lock check, on the result: the resolved value at every inherited locked path is
     * unchanged. It makes a stateOrder lock hold against anchors, additions and removals, none of
     * which touch the path "stateOrder", and backs up the first check. Anchors are compared
     * stripped, since they are level-local.
     */
    static List<Violation> lockedValues(List<String> locks, Map<String, Object> parentResolved, Map<String, Object> effective) {
        List<Violation> v = new ArrayList<>();
        Map<String, Object> before = strip(parentResolved);
        Map<String, Object> after = strip(effective);
        for (String l : locks) {
            Object was = at(before, l);
            Object now = at(after, l);
            if (!Objects.equals(was, now)) {
                v.add(Violation.of(l, "locked by an ancestor template; its value would change from "
                        + compact(was) + " to " + compact(now)));
            }
        }
        return v;
    }

    /** The value at a dotted path ("states.qa.wipLimit", "stateOrder"), or null. */
    static Object at(Map<String, Object> doc, String path) {
        Object x = doc;
        for (String seg : path.split("\\.")) {
            x = x instanceof Map<?, ?> m ? m.get(seg) : null;
        }
        return x;
    }

    // ---- provenance -----------------------------------------------------

    /**
     * Which level last set each value, at field level: "states.in-review.name" → "acme@1". The
     * root sets everything it defines; each later level claims the paths its delta sets, and a
     * tombstone drops the element's entries.
     */
    static Map<String, String> provenance(List<TemplateVersion> chain, Map<String, Object> workspaceDelta) {
        Map<String, String> out = new TreeMap<>();
        chain.forEach(x -> claim(out, sections(x.document()), x.ref()));
        if (workspaceDelta != null) {
            claim(out, workspaceDelta, TemplateResolver.WORKSPACE);
        }
        return out;
    }

    private static void claim(Map<String, String> out, Map<String, Object> delta, String level) {
        delta.forEach((s, x) -> {
            if (LevelResolver.KEYED.contains(s)) {
                obj(x).forEach((k, el) -> {
                    if (el == null) {
                        out.keySet().removeIf(p -> p.startsWith(s + "." + k + "."));
                        return;
                    }
                    obj(el).forEach((f, val) -> {
                        if (val == null) {
                            out.remove(s + "." + k + "." + f);
                        } else {
                            out.put(s + "." + k + "." + f, level);
                        }
                    });
                });
            } else if (x instanceof Map<?, ?> m) {
                obj(m).keySet().forEach(f -> out.put(s + "." + f, level));
            } else {
                out.put(s, level);
            }
        });
    }
}
