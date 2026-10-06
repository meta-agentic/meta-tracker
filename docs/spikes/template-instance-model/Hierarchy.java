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
// pass), and propagation (what a publish does to every descendant, via Resolver.upgrade).

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

    /** Root, then at most six template levels, then the workspace: base, methodology, organisation, unit, team, workspace fit with room to spare. */
    static final int MAX_LEVELS = 8;
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
            if (out.size() >= MAX_LEVELS) {
                v.add("extends: more than " + (MAX_LEVELS - 1) + " template levels; a workspace needs the last one");
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
        if (!locks.isEmpty()) {
            parent.put("locks", new ArrayList<>(locks));
        }
        Resolver.Resolution res = Resolver.resolve(parent, delta);
        v.addAll(res.violations());
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

    /** Resolve a whole chain, root first, level by level. Used at publish; reads use the materialised result. */
    static Resolver.Resolution resolveChain(List<Version> chain) {
        List<String> v = new ArrayList<>();
        Version root = chain.getFirst();
        Resolver.Resolution r = Resolver.resolve(root.document(), Map.of());
        r.violations().forEach(x -> v.add(root.ref() + ": " + x));
        Map<String, Object> doc = r.effective();
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
        Resolver.Resolution res = Resolver.resolve(strip(parent), w.delta);
        v.addAll(res.violations());
        return new Resolver.Resolution(res.effective(), v);
    }

    /** A workspace delta write: VEC-45's writeDelta, plus the ancestors' locks. */
    static Resolver.Resolution writeWorkspaceDelta(Registry r, Workspace w, Map<String, Object> newDelta) {
        Map<String, Object> parent = r.resolved.get(ref(w.template, w.version));
        List<String> v = new ArrayList<>(lockViolations(Json.strings(parent.get("locks")), newDelta));
        Resolver.Resolution res = Resolver.writeDelta(strip(parent), w.delta, newDelta, w.occupancy);
        v.addAll(res.violations());
        return new Resolver.Resolution(res.effective(), v);
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

    /** A path a delta touches; a removal (a tombstone) also touches everything under it. */
    record Touch(String path, boolean removal) {
        boolean hits(String lock) {
            return path.equals(lock) || path.startsWith(lock + ".") || removal && lock.startsWith(path + ".");
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
     * A delta may not touch a locked path or anything under it, and may not remove an element
     * that contains one: a tombstone of "states.gate-1" touches the lock "states.gate-1.gate".
     * Overriding another field of the same element ("states.gate-1.name") is allowed.
     */
    static List<String> lockViolations(List<String> locks, Map<String, Object> delta) {
        List<String> v = new ArrayList<>();
        for (Touch t : touches(Json.obj(delta))) {
            locks.stream().filter(t::hits).findFirst()
                    .ifPresent(l -> v.add(t.path() + ": locked by an ancestor template ('" + l + "')"));
        }
        return v;
    }

    // ---- publishing -----------------------------------------------------

    /**
     * Publish a new version. It must be the key's next version, by the key's owner; extend a
     * published version owned by the system or by the same owner (a tenant never builds on another
     * tenant's template); stay within MAX_LEVELS; respect every ancestor lock; and resolve cleanly
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

    // ---- propagation ----------------------------------------------------

    /** One edge's upgrade: VEC-45's Resolver.upgrade between two resolved parent versions, plus the new parent's locks. */
    static Resolver.Upgrade upgradeEdge(Registry r, Map<String, Object> delta, String from, String to, Map<String, Integer> occupancy) {
        Map<String, Object> d = Json.obj(Json.deepCopy(delta));
        Object ownLocks = d.remove("locks");
        Map<String, Object> target = r.resolved.get(to);
        Resolver.Upgrade u = Resolver.upgrade(strip(r.resolved.get(from)), strip(target), d, occupancy);
        List<String> conflicts = new ArrayList<>(u.conflicts());
        conflicts.addAll(lockViolations(Json.strings(target.get("locks")), u.delta()));
        Map<String, Object> out = u.delta();
        if (ownLocks != null) {
            out.put("locks", ownLocks);
        }
        return new Resolver.Upgrade(out, u.effective(), u.notes(), conflicts);
    }

    /**
     * What publishing {@code key@version} does to its descendants. Walks the tree top-down. Each
     * direct child (template or workspace) pinned to an older version of {@code key} is dry-run
     * upgraded. A child that follows automatically and upgrades without conflicts is upgraded: a
     * template child publishes its own next version, rebased (and its children are then visited in
     * turn); a workspace is re-pinned and its revision bumped, which emits configuration.changed
     * with cause "ancestor" naming the version originally published. Anything else stays pinned
     * where it is, and the plan says why: a manual follower gets the dry run as a preview, a
     * conflicting one gets the conflicts. Nothing is ever partly upgraded.
     */
    static List<String> propagate(Registry r, List<Workspace> workspaces, String key, long version, String origin) {
        List<String> plan = new ArrayList<>();
        String to = ref(key, version);
        Set<String> children = new TreeSet<>();
        r.versions.values().forEach(x -> {
            if (x.parent() != null && x.parent().startsWith(key + "@") && r.latest(x.key()) == x.version()) {
                children.add(x.key());
            }
        });
        for (String c : children) {
            Version cv = r.get(c, r.latest(c));
            if (cv.parent().equals(to)) {
                continue;
            }
            Resolver.Upgrade u = upgradeEdge(r, withLocks(cv), cv.parent(), to, Map.of());
            if (!"auto".equals(r.follow.get(c))) {
                plan.add("template " + cv.ref() + ": stays on " + cv.parent() + ", manual" + summary(u));
                continue;
            }
            if (!u.conflicts().isEmpty()) {
                plan.add("template " + cv.ref() + ": blocked, stays on " + cv.parent() + ": " + u.conflicts());
                continue;
            }
            Map<String, Object> next = new LinkedHashMap<>();
            for (String m : List.of("template", "version", "name", "description")) {
                if (cv.document().containsKey(m)) {
                    next.put(m, cv.document().get(m));
                }
            }
            next.put("version", cv.version() + 1);
            next.put("extends", Json.parse("{\"template\": \"" + key + "\", \"version\": " + version + "}"));
            next.putAll(u.delta());
            List<String> v = publish(r, cv.owner(), next);
            if (!v.isEmpty()) {
                plan.add("template " + cv.ref() + ": blocked, stays on " + cv.parent() + ": " + v);
                continue;
            }
            plan.add("template " + cv.ref() + " -> " + ref(c, cv.version() + 1) + ", rebased onto " + to + summary(u));
            plan.addAll(propagate(r, workspaces, c, cv.version() + 1, origin));
        }
        for (Workspace w : workspaces.stream().sorted((a, b) -> a.key.compareTo(b.key)).toList()) {
            if (!w.template.equals(key) || w.version >= version) {
                continue;
            }
            String from = ref(w.template, w.version);
            Resolver.Upgrade u = upgradeEdge(r, w.delta, from, to, w.occupancy);
            if (!w.auto) {
                plan.add("workspace " + w.key + ": stays on " + from + ", manual" + summary(u));
            } else if (!u.conflicts().isEmpty()) {
                plan.add("workspace " + w.key + ": blocked, stays on " + from + ": " + u.conflicts());
            } else {
                Map<String, Object> before = resolveWorkspace(r, w).effective();
                w.version = version;
                w.delta = u.delta();
                w.revision++;
                plan.add("workspace " + w.key + ": " + from + " -> " + to + ", revision " + w.revision
                        + ", configuration.changed cause ancestor " + origin + " " + Diff.projection(before, resolveWorkspace(r, w).effective()));
            }
        }
        return plan;
    }

    private static Map<String, Object> withLocks(Version v) {
        Map<String, Object> d = sections(v.document());
        if (v.document().containsKey("locks")) {
            d.put("locks", Json.deepCopy(v.document().get("locks")));
        }
        return d;
    }

    private static String summary(Resolver.Upgrade u) {
        return u.conflicts().isEmpty() ? (u.notes().isEmpty() ? "" : "; notes " + u.notes()) : "; would conflict: " + u.conflicts();
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
