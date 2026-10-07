// What publishing a template version does to its descendants (spike, throwaway).
//
// Two paths, both built on VEC-45's Resolver.upgrade run edge by edge:
//   - release(): a built-in version ships in a Vectis release. Its built-in descendants are
//     rebased at BUILD time, by the same function, into new reviewed files in the release
//     (one file per version stays true); a built-in edge that does not rebase cleanly fails the
//     build. Only then do the runtime edges run.
//   - propagate(): the runtime edges, tenant templates and workspaces. A child that follows
//     automatically and upgrades without conflicts is upgraded; anything else stays pinned and
//     the plan says why. Built-in children are never published at runtime.
// A workspace upgrade is planned, then applied in the workspace's own transaction, which
// recomputes it from the workspace's current pin and delta: the plan is only a preview, so a
// delta written between plan and apply is never overwritten.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class Propagation {

    private Propagation() {
    }

    /** A rebased template version: the upgrade, and the document of the next version it implies. */
    record Rebase(Resolver.Upgrade upgrade, Map<String, Object> document) {
    }

    /** A planned workspace upgrade: what the plan saw (pin, revision) and its preview. */
    record Planned(Hierarchy.Workspace workspace, String from, long revision, Resolver.Upgrade preview) {
    }

    /** What a release ships: the files it adds (the published version first, then the rebased built-ins) and the runtime plan. */
    record Release(List<Map<String, Object>> files, List<String> plan) {
    }

    /** One edge's upgrade: VEC-45's upgrade between two resolved parent versions, plus both lock checks against the new parent. */
    static Resolver.Upgrade upgradeEdge(Hierarchy.Registry r, Map<String, Object> delta, String from, String to, Map<String, Integer> occupancy) {
        Map<String, Object> d = Json.obj(Json.deepCopy(delta));
        Object ownLocks = d.remove("locks");
        Map<String, Object> target = r.resolved.get(to);
        Map<String, Object> onto = Hierarchy.forValidation(target);
        if (ownLocks != null && !target.containsKey("deliveryGates") && !Boolean.TRUE.equals(Json.obj(target.get("settings")).get("gatedDelivery"))) {
            // As in Hierarchy.derive: a template child that enables gatedDelivery itself validates with its own locks.
            Set<String> all = new TreeSet<>(Json.strings(target.get("locks")));
            all.addAll(Json.strings(ownLocks));
            onto.put("locks", new ArrayList<>(all));
        }
        Resolver.Upgrade u = Resolver.upgrade(Hierarchy.strip(r.resolved.get(from)), onto, d, occupancy);
        Hierarchy.restoreLocks(target, u.effective());
        List<String> conflicts = new ArrayList<>(u.conflicts());
        List<String> locks = Json.strings(target.get("locks"));
        conflicts.addAll(Hierarchy.lockViolations(locks, u.delta()));
        conflicts.addAll(Hierarchy.lockedValues(locks, target, u.effective()));
        Map<String, Object> out = u.delta();
        if (ownLocks != null) {
            out.put("locks", ownLocks);
        }
        return new Resolver.Upgrade(out, u.effective(), u.notes(), conflicts);
    }

    /** The next version of a template child, rebased onto {@code to}: same identity, next version, the rewritten delta. */
    static Rebase rebase(Hierarchy.Registry r, Hierarchy.Version child, String to) {
        Map<String, Object> d = Hierarchy.sections(child.document());
        if (child.document().containsKey("locks")) {
            d.put("locks", Json.deepCopy(child.document().get("locks")));
        }
        Resolver.Upgrade u = upgradeEdge(r, d, child.parent(), to, Map.of());
        Map<String, Object> next = new LinkedHashMap<>();
        for (String m : List.of("template", "version", "name", "description")) {
            if (child.document().containsKey(m)) {
                next.put(m, child.document().get(m));
            }
        }
        next.put("version", child.version() + 1);
        String[] t = to.split("@");
        next.put("extends", Json.parse("{\"template\": \"" + t[0] + "\", \"version\": " + t[1] + "}"));
        next.putAll(u.delta());
        return new Rebase(u, next);
    }

    /** Template children of {@code key} whose latest version is pinned to an OLDER version of it: never a downgrade, never a repeat. */
    static Set<String> behind(Hierarchy.Registry r, String key, long version) {
        Set<String> out = new TreeSet<>();
        r.versions.values().forEach(x -> {
            String p = x.parent();
            if (p != null && p.startsWith(key + "@") && r.latest(x.key()) == x.version() && Long.parseLong(p.substring(key.length() + 1)) < version) {
                out.add(x.key());
            }
        });
        return out;
    }

    /** A built-in release: publish {@code document}, rebase every built-in below it as the release build would, then run the runtime edges. */
    static Release release(Hierarchy.Registry r, List<Hierarchy.Workspace> workspaces, Map<String, Object> document) {
        List<Map<String, Object>> files = new ArrayList<>();
        List<String> plan = new ArrayList<>(Hierarchy.publish(r, Hierarchy.SYSTEM, document));
        if (!plan.isEmpty()) {
            return new Release(files, plan);
        }
        files.add(document);
        String origin = Hierarchy.ref((String) document.get("template"), (Long) document.get("version"));
        for (int i = 0; i < files.size(); i++) {
            String key = (String) files.get(i).get("template");
            long version = (Long) files.get(i).get("version");
            for (String c : behind(r, key, version)) {
                Hierarchy.Version cv = r.get(c, r.latest(c));
                if (!cv.owner().equals(Hierarchy.SYSTEM)) {
                    continue;
                }
                Rebase rb = rebase(r, cv, Hierarchy.ref(key, version));
                List<String> v = rb.upgrade().conflicts().isEmpty() ? Hierarchy.publish(r, Hierarchy.SYSTEM, rb.document()) : rb.upgrade().conflicts();
                if (v.isEmpty()) {
                    files.add(rb.document());
                } else {
                    plan.add("build fails: built-in " + cv.ref() + " does not rebase onto " + Hierarchy.ref(key, version) + ": " + v);
                }
            }
        }
        for (Map<String, Object> f : files) {
            plan.addAll(propagate(r, workspaces, (String) f.get("template"), (Long) f.get("version"), origin));
        }
        return new Release(files, plan);
    }

    /**
     * The runtime edges below {@code key@version}, top-down. Each tenant template child and each
     * workspace pinned to an older version is dry-run upgraded. An automatic follower with a clean
     * upgrade is upgraded (a template publishes its rebased next version and its own children are
     * then discovered and visited; a workspace is planned and applied); anything else stays pinned,
     * with the conflicts or, for a manual follower, the dry run as a preview. Built-in children are
     * skipped: they are rebased by the release build, never at runtime.
     */
    static List<String> propagate(Hierarchy.Registry r, List<Hierarchy.Workspace> workspaces, String key, long version, String origin) {
        List<String> plan = new ArrayList<>();
        String to = Hierarchy.ref(key, version);
        for (String c : behind(r, key, version)) {
            Hierarchy.Version cv = r.get(c, r.latest(c));
            if (cv.owner().equals(Hierarchy.SYSTEM)) {
                plan.add("template " + cv.ref() + ": built-in, rebased by the release build, not at runtime");
                continue;
            }
            Rebase rb = rebase(r, cv, to);
            Resolver.Upgrade u = rb.upgrade();
            if (!"auto".equals(r.follow.get(c))) {
                plan.add("template " + cv.ref() + ": stays on " + cv.parent() + ", manual" + summary(u));
                continue;
            }
            List<String> v = u.conflicts().isEmpty() ? Hierarchy.publish(r, cv.owner(), rb.document()) : u.conflicts();
            if (!v.isEmpty()) {
                plan.add("template " + cv.ref() + ": blocked, stays on " + cv.parent() + ": " + v);
                continue;
            }
            plan.add("template " + cv.ref() + " -> " + Hierarchy.ref(c, cv.version() + 1) + ", rebased onto " + to + summary(u));
            plan.addAll(propagate(r, workspaces, c, cv.version() + 1, origin));
        }
        for (Hierarchy.Workspace w : workspaces.stream().sorted((a, b) -> a.key.compareTo(b.key)).toList()) {
            if (!w.template.equals(key) || w.version >= version) {
                continue;
            }
            Planned p = plan(r, w, to);
            if (!w.auto) {
                plan.add("workspace " + w.key + ": stays on " + p.from() + ", manual" + summary(p.preview()));
            } else {
                plan.add(apply(r, p, to, origin));
            }
        }
        return plan;
    }

    /** The plan for one workspace: what it is pinned to, at which revision, and the dry run. A preview only. */
    static Planned plan(Hierarchy.Registry r, Hierarchy.Workspace w, String to) {
        String from = Hierarchy.ref(w.template, w.version);
        return new Planned(w, from, w.revision, upgradeEdge(r, w.delta, from, to, w.occupancy));
    }

    /**
     * The workspace's own transaction. Under its row lock: if it is already at or past the target
     * (a manual upgrade, or an earlier row, got there first), skip; otherwise recompute the upgrade
     * from its CURRENT pin, delta and occupancy, never the plan's preview, so a delta written since
     * the plan is kept and a pin moved by an earlier row is upgraded from where it now is; refuse on
     * conflicts; else re-pin, store, bump the revision, reconcile, emit the event.
     */
    static String apply(Hierarchy.Registry r, Planned p, String to, String origin) {
        Hierarchy.Workspace w = p.workspace();
        String pin = Hierarchy.ref(w.template, w.version);
        String[] t = to.split("@");
        if (!w.template.equals(t[0]) || w.version >= Long.parseLong(t[1])) {
            return "workspace " + w.key + ": skipped, already on " + pin + ", at or past " + to;
        }
        Resolver.Upgrade u = upgradeEdge(r, w.delta, pin, to, w.occupancy);
        String moved = (pin.equals(p.from()) ? "" : ", re-planned from " + pin + " (the plan saw " + p.from() + ")")
                + (w.revision == p.revision() ? "" : ", re-planned from revision " + w.revision + " (the plan saw " + p.revision() + ")");
        if (!u.conflicts().isEmpty()) {
            return "workspace " + w.key + ": blocked, stays on " + pin + ": " + u.conflicts() + moved;
        }
        Map<String, Object> before = Hierarchy.resolveWorkspace(r, w).effective();
        w.template = t[0];
        w.version = Long.parseLong(t[1]);
        w.delta = u.delta();
        w.revision++;
        return "workspace " + w.key + ": " + pin + " -> " + to + ", revision " + w.revision + moved
                + ", configuration.changed cause ancestor " + origin + " " + Diff.projection(before, Hierarchy.resolveWorkspace(r, w).effective());
    }

    private static String summary(Resolver.Upgrade u) {
        return u.conflicts().isEmpty() ? (u.notes().isEmpty() ? "" : "; notes " + u.notes()) : "; would conflict: " + u.conflicts();
    }
}
