// Template hierarchy: templates that extend templates, down to the workspace (spike, throwaway).
//
// VEC-45's model had one template level: a workspace = pinned template version + delta. Here
// a template version is itself either a root (a complete document) or derived: it names the
// exact parent version it extends and carries a delta over that parent's resolved document,
// in the same vocabulary as a workspace delta. A workspace is simply the last level. So
//
//     resolved(root)  = resolve(root, {})
//     resolved(T@n)   = resolve(strip(resolved(parent of T@n)), delta(T@n))
//     effective(ws)   = resolve(strip(resolved(T@n)), ws.delta)
//
// where resolve() is VEC-45's Resolver.resolve, unchanged. Every edge is pinned to a parent
// version, and versions are immutable, so a resolved template version never changes and is
// materialised once, at publish. Reading a workspace is still a two-document merge.
//
// strip() drops the parent's placement anchors: anchors are level-local. Each level's anchors
// are resolved against its parent's resolved order and then become plain order for the next
// level, so two levels anchoring next to the same state never collide (case H3).
//
// Also here: locks (paths no descendant may override), publishing (what a new version must
// pass) and provenance. What a publish does to descendants is in Propagation.java.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class Hierarchy {

    private Hierarchy() {
    }

    /**
     * The most template versions a chain may hold, root included; the workspace is not counted.
     * Base, methodology, organisation, unit, team: five, the PO's limit (ADR-VEC-03, Q2).
     */
    static final int MAX_TEMPLATE_LEVELS = 5;
    /** Document members that say what a version is, not what it configures; never part of a delta. */
    static final Set<String> IDENTITY = Set.of("template", "version", "name", "description", "extends");
    /** The owner of built-in templates, shipped by a Vectis release. Any other owner is a tenant. */
    static final String SYSTEM = "system";

    /** A published template version as stored: the authored document and the owner of its key. */
    record Version(String key, long version, String owner, Map<String, Object> document) {
        String ref() {
            return Hierarchy.ref(key, version);
        }

        /** The parent this version extends, as "key@version", or null for a root. */
        String parent() {
            Map<String, Object> e = Json.obj(document.get("extends"));
            return e.isEmpty() ? null : Hierarchy.ref((String) e.get("template"), (Long) e.get("version"));
        }
    }

    /** A workspace: the last level. It follows its template automatically or by explicit upgrade. */
    static final class Workspace {
        final String key;
        String template;
        long version;
        Map<String, Object> delta;
        final boolean auto;
        final Map<String, Integer> occupancy;
        long revision;

        Workspace(String key, String template, long version, Map<String, Object> delta, boolean auto, Map<String, Integer> occupancy) {
            this.key = key;
            this.template = template;
            this.version = version;
            this.delta = delta;
            this.auto = auto;
            this.occupancy = occupancy;
        }
    }

    /** The published versions. Append-only: a version, once in, is never replaced. */
    static final class Registry {
        final Map<String, Version> versions = new TreeMap<>();
        /** Materialised at publish; a cache of an immutable function of immutable inputs. */
        final Map<String, Map<String, Object>> resolved = new TreeMap<>();
        /** Per template key: whether it follows its parent automatically ("auto") or by explicit upgrade (default). */
        final Map<String, String> follow = new TreeMap<>();

        long latest(String key) {
            return versions.values().stream().filter(x -> x.key().equals(key)).mapToLong(Version::version).max().orElse(0);
        }

        Version get(String key, long version) {
            return versions.get(ref(key, version));
        }
    }

    static String ref(String key, long version) {
        return key + "@" + version;
    }

    // ---- resolution ---------------------------------------------------

    /** The chain from the root down to {@code leaf}, root first. Violations if a link is missing or the chain is too deep. */
    static List<Version> chain(Registry r, Version leaf, List<String> v) {
        List<Version> out = new ArrayList<>();
        for (Version x = leaf; x != null; ) {
            out.addFirst(x);
            if (out.size() > MAX_TEMPLATE_LEVELS) {
                v.add("extends: more than " + MAX_TEMPLATE_LEVELS + " template levels, root included");
                break;
            }
            String p = x.parent();
            x = p == null ? null : r.versions.get(p);
            if (p != null && x == null) {
                v.add("extends: " + p + " is not a published template version");
            }
        }
        return out;
    }

    /** Resolve one derived level over its parent's resolved document. Violations are prefixed with the level. */
    static Resolver.Resolution derive(Map<String, Object> parentResolved, Map<String, Object> document, String level) {
        Map<String, Object> parent = strip(parentResolved);
        Map<String, Object> delta = sections(document);
        List<String> v = new ArrayList<>(lockViolations(Json.strings(parent.get("locks")), delta));
        Set<String> locks = new TreeSet<>(Json.strings(parent.get("locks")));
        locks.addAll(Json.strings(document.get("locks")));
        // Which gates count for gatedDelivery (Validation.gatedDelivery counts the gates locked in the
        // document it validates):
        //  - once the rule is binding (an ancestor locked settings.gatedDelivery), only the gates frozen
        //    at that level, carried down as deliveryGates; a gate locked anywhere below never counts,
        //    so no level can vouch for a gate that it or its descendants then use (L5);
        //  - while the rule is inherited but not binding, only the parent's locks: a level cannot
        //    vouch for a gate it added itself;
        //  - at a level that turns the rule on, its own locks count.
        // In the first two cases this level's own locks join after validation.
        List<String> frozen = parentResolved.get("deliveryGates") instanceof List<?> ? Json.strings(parentResolved.get("deliveryGates")) : null;
        boolean inherited = Boolean.TRUE.equals(Json.obj(parent.get("settings")).get("gatedDelivery"));
        if (frozen != null) {
            parent.put("locks", frozen);
        } else if (!inherited && !locks.isEmpty()) {
            parent.put("locks", new ArrayList<>(locks));
        }
        Resolver.Resolution res = Resolver.resolve(parent, delta);
        v.addAll(res.violations());
        if ((frozen != null || inherited) && !locks.isEmpty()) {
            res.effective().put("locks", new ArrayList<>(locks));
            Validation.locks(res.effective(), v);
        }
        if (frozen == null) {
            freezeDeliveryGates(res.effective());
        }
        v.addAll(lockedValues(Json.strings(parentResolved.get("locks")), parentResolved, res.effective()));
        Map<String, Object> eff = res.effective();
        for (String m : List.of("template", "version", "name", "description", "extends")) {
            eff.remove(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String m : List.of("template", "version", "name", "description", "extends")) {
            if (document.containsKey(m)) {
                out.put(m, Json.deepCopy(document.get(m)));
            }
        }
        out.putAll(eff);
        if (!(document.get("name") instanceof String n) || n.isBlank()) {
            v.add("name: a template version needs its own name");
        }
        v.replaceAll(x -> level + ": " + x);
        return new Resolver.Resolution(out, v);
    }

    /**
     * The level where gatedDelivery becomes binding (it is on and settings.gatedDelivery is locked)
     * freezes the gates that govern it: the gate locks it holds, "states.k.gate" or a whole "states.k".
     * Recorded in the resolved document as deliveryGates, which no document may author (it is not a
     * section), and inherited unchanged by every level and workspace below.
     */
    static void freezeDeliveryGates(Map<String, Object> eff) {
        List<String> locks = Json.strings(eff.get("locks"));
        if (Boolean.TRUE.equals(Json.obj(eff.get("settings")).get("gatedDelivery")) && locks.contains("settings.gatedDelivery")) {
            eff.put("deliveryGates", locks.stream()
                    .filter(l -> l.startsWith("states.") && (l.endsWith(".gate") || l.split("\\.").length == 2)).toList());
        }
    }

    /** The resolved template version as a workspace or child validates against it: under a binding rule, only the frozen gates count. */
    static Map<String, Object> forValidation(Map<String, Object> resolved) {
        Map<String, Object> doc = strip(resolved);
        if (resolved.get("deliveryGates") instanceof List<?>) {
            doc.put("locks", Json.strings(resolved.get("deliveryGates")));
        }
        return doc;
    }

    /** Resolve a whole chain, root first, level by level. Used at publish; reads use the materialised result. */
    static Resolver.Resolution resolveChain(List<Version> chain) {
        List<String> v = new ArrayList<>();
        Version root = chain.getFirst();
        Map<String, Object> rootDoc = Json.obj(Json.deepCopy(root.document()));
        if (rootDoc.remove("deliveryGates") != null) {
            v.add(root.ref() + ": deliveryGates: set by resolution, never authored");
        }
        Resolver.Resolution r = Resolver.resolve(rootDoc, Map.of());
        r.violations().forEach(x -> v.add(root.ref() + ": " + x));
        Map<String, Object> doc = r.effective();
        freezeDeliveryGates(doc);
        for (Version x : chain.subList(1, chain.size())) {
            Resolver.Resolution d = derive(doc, x.document(), x.ref());
            v.addAll(d.violations());
            doc = d.effective();
        }
        return new Resolver.Resolution(doc, v);
    }

    /** A workspace's effective configuration: VEC-45's two-document resolution over the materialised template version. */
    static Resolver.Resolution resolveWorkspace(Registry r, Workspace w) {
        Map<String, Object> parent = r.resolved.get(ref(w.template, w.version));
        List<String> v = new ArrayList<>(lockViolations(Json.strings(parent.get("locks")), w.delta));
        Resolver.Resolution res = Resolver.resolve(forValidation(parent), w.delta);
        v.addAll(res.violations());
        restoreLocks(parent, res.effective());
        v.addAll(lockedValues(Json.strings(parent.get("locks")), parent, res.effective()));
        return new Resolver.Resolution(res.effective(), v);
    }

    /** A workspace delta write: VEC-45's writeDelta, plus the ancestors' locks. */
    static Resolver.Resolution writeWorkspaceDelta(Registry r, Workspace w, Map<String, Object> newDelta) {
        Map<String, Object> parent = r.resolved.get(ref(w.template, w.version));
        List<String> v = new ArrayList<>(lockViolations(Json.strings(parent.get("locks")), newDelta));
        Resolver.Resolution res = Resolver.writeDelta(forValidation(parent), w.delta, newDelta, w.occupancy);
        v.addAll(res.violations());
        restoreLocks(parent, res.effective());
        v.addAll(lockedValues(Json.strings(parent.get("locks")), parent, res.effective()));
        return new Resolver.Resolution(res.effective(), v);
    }

    /** After validating against the frozen gates, the effective document shows the parent's full locks again. */
    static void restoreLocks(Map<String, Object> parent, Map<String, Object> eff) {
        if (parent.containsKey("locks")) {
            eff.put("locks", Json.deepCopy(parent.get("locks")));
        }
    }

    /** The parent's resolved document as the next level sees it: a deep copy without placement anchors. */
    static Map<String, Object> strip(Map<String, Object> resolved) {
        Map<String, Object> doc = Json.obj(Json.deepCopy(resolved));
        Json.obj(doc.get("states")).values().forEach(s -> {
            Json.obj(s).remove("after");
            Json.obj(s).remove("before");
        });
        return doc;
    }

    /** A derived document's delta: everything but its identity and its own locks. */
    static Map<String, Object> sections(Map<String, Object> document) {
        Map<String, Object> d = new LinkedHashMap<>();
        document.forEach((k, x) -> {
            if (!IDENTITY.contains(k) && !k.equals("locks")) {
                d.put(k, Json.deepCopy(x));
            }
        });
        return d;
    }

    // ---- locks ----------------------------------------------------------

    /** A path a delta touches. It hits a lock on the same path, above it, or below it (setting or removing an element reaches its locked member). */
    record Touch(String path, boolean removal) {
        boolean hits(String lock) {
            return path.equals(lock) || path.startsWith(lock + ".") || lock.startsWith(path + ".");
        }
    }

    /** Every path a delta touches, at field level: "states.qa.wipLimit", "settings.defaultItemType", "states.bug" for a tombstone, "stateOrder". */
    static List<Touch> touches(Map<String, Object> delta) {
        List<Touch> out = new ArrayList<>();
        for (String s : new TreeSet<>(delta.keySet())) {
            if (!(delta.get(s) instanceof Map<?, ?> section)) {
                out.add(new Touch(s, false));
                continue;
            }
            Json.obj(section).forEach((k, el) -> {
                if (!Resolver.KEYED.contains(s) || el == null) {
                    out.add(new Touch(s + "." + k, el == null));
                } else if (el instanceof Map<?, ?> m) {
                    Json.obj(m).forEach((f, x) -> out.add(new Touch(s + "." + k + "." + f, x == null)));
                }
            });
        }
        return out;
    }

    /**
     * A delta may not touch a locked path, anything under it, or an element containing it: a
     * tombstone of "states.gate-1" touches the lock "states.gate-1.gate". Overriding another field
     * of the same element ("states.gate-1.name") is allowed. This is the first of two checks; see
     * lockedValues for the second.
     */
    static List<String> lockViolations(List<String> locks, Map<String, Object> delta) {
        List<String> v = new ArrayList<>();
        for (Touch t : touches(Json.obj(delta))) {
            locks.stream().filter(t::hits).findFirst()
                    .ifPresent(l -> v.add(t.path() + ": locked by an ancestor template ('" + l + "')"));
        }
        return v;
    }

    /**
     * The second lock check, on the result rather than the delta: the resolved value at every
     * inherited locked path is unchanged. It is what makes a stateOrder lock hold against anchors,
     * additions and removals, none of which touch the path "stateOrder", and it is the backstop for
     * any other way to change a locked value without naming it. Anchors are compared stripped,
     * since they are level-local.
     */
    static List<String> lockedValues(List<String> locks, Map<String, Object> parentResolved, Map<String, Object> effective) {
        List<String> v = new ArrayList<>();
        Map<String, Object> before = strip(parentResolved);
        Map<String, Object> after = strip(effective);
        for (String l : locks) {
            Object was = at(before, l);
            Object now = at(after, l);
            if (!java.util.Objects.equals(was, now)) {
                v.add(l + ": locked by an ancestor template; its value would change from " + Json.compact(was) + " to " + Json.compact(now));
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

    // ---- publishing -----------------------------------------------------

    /**
     * Publish a new version. It must be the key's next version, by the key's owner; extend a
     * published version owned by the system or by the same owner (a tenant never builds on another
     * tenant's template); stay within MAX_TEMPLATE_LEVELS; respect every ancestor lock; and resolve cleanly
     * on its own, so every level is itself a valid, provisionable workflow. Roots are built-in only.
     * Returns the violations; when there are none the version is in, with its resolution materialised.
     */
    static List<String> publish(Registry r, String owner, Map<String, Object> document) {
        List<String> v = new ArrayList<>();
        String key = document.get("template") instanceof String k ? k : "";
        long version = document.get("version") instanceof Long n ? n : 0;
        if (!Resolver.KEY.matcher(key).matches()) {
            v.add("template: key must match " + Resolver.KEY.pattern());
        }
        if (version != r.latest(key) + 1) {
            v.add("version: " + key + " is at version " + r.latest(key) + "; a new version is " + (r.latest(key) + 1)
                    + " (published versions are immutable)");
        }
        r.versions.values().stream().filter(x -> x.key().equals(key) && !x.owner().equals(owner)).findFirst()
                .ifPresent(x -> v.add("template: '" + key + "' belongs to " + x.owner()));
        Version me = new Version(key, version, owner, document);
        if (me.parent() == null) {
            if (!owner.equals(SYSTEM)) {
                v.add("extends: a tenant template extends a built-in or one of its own templates; roots are built-in");
            }
        } else if (r.versions.get(me.parent()) instanceof Version p && !p.owner().equals(SYSTEM) && !p.owner().equals(owner)) {
            v.add("extends: " + p.ref() + " belongs to another owner");
        }
        if (!v.isEmpty()) {
            return v;
        }
        List<Version> chain = chain(r, me, v);
        if (!v.isEmpty()) {
            return v;
        }
        Resolver.Resolution res = resolveChain(chain);
        if (!res.ok()) {
            return res.violations();
        }
        r.versions.put(me.ref(), me);
        r.resolved.put(me.ref(), res.effective());
        return v;
    }

    // ---- provenance -----------------------------------------------------

    /**
     * Which level last set each value of the effective configuration, at field level:
     * "states.in-review.name" -> "acme@1". The root sets everything it defines; each later level
     * claims the paths its delta sets, and a tombstone drops the element's entries. This is what a
     * reviewer needs next to the effective document: not only what the value is, but whose it is.
     */
    static Map<String, String> provenance(List<Version> chain, Map<String, Object> workspaceDelta) {
        Map<String, String> out = new TreeMap<>();
        Version root = chain.getFirst();
        claim(out, sections(root.document()), root.ref());
        for (Version x : chain.subList(1, chain.size())) {
            claim(out, sections(x.document()), x.ref());
        }
        if (workspaceDelta != null) {
            claim(out, workspaceDelta, "workspace");
        }
        return out;
    }

    private static void claim(Map<String, String> out, Map<String, Object> delta, String level) {
        for (String s : delta.keySet()) {
            Object x = delta.get(s);
            if (Resolver.KEYED.contains(s)) {
                Json.obj(x).forEach((k, el) -> {
                    if (el == null) {
                        out.keySet().removeIf(p -> p.startsWith(s + "." + k + "."));
                    } else {
                        Json.obj(el).forEach((f, val) -> {
                            if (val == null) {
                                out.remove(s + "." + k + "." + f);
                            } else {
                                out.put(s + "." + k + "." + f, level);
                            }
                        });
                    }
                });
            } else if (x instanceof Map<?, ?> m) {
                Json.obj(m).keySet().forEach(f -> out.put(s + "." + f, level));
            } else {
                out.put(s, level);
            }
        }
    }
}
