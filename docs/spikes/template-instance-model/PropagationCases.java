// Cases for mutation and propagation in the template hierarchy (VEC-66): what publishing a new
// version at any level does to every descendant template and workspace. Called from MethodologyCases.

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class PropagationCases extends Cases {

    static Map<String, Object> h(String file) throws Exception {
        return HierarchyCases.h(file);
    }

    /** A new version of a built-in, authored as its previous document plus a change. */
    static Map<String, Object> nextOf(Hierarchy.Registry r, String key, String change) {
        Hierarchy.Version v = r.get(key, r.latest(key));
        Map<String, Object> doc = Json.obj(Json.mergePatch(v.document(), patch(change)));
        doc.put("version", v.version() + 1);
        return doc;
    }

    static Hierarchy.Workspace ws(String key, String template, String delta, boolean auto, Map<String, Integer> occupancy) {
        return new Hierarchy.Workspace(key, template, 1, patch(delta), auto, occupancy);
    }

    static Map<String, Object> eff(Hierarchy.Registry r, Hierarchy.Workspace w) {
        return Hierarchy.resolveWorkspace(r, w).effective();
    }

    /** A Vectis release of a new built-in version: the build rebases the built-ins below it, then the runtime edges run. */
    static List<String> publishAndPropagate(Hierarchy.Registry r, List<Hierarchy.Workspace> all, String key, String change) {
        return Propagation.release(r, all, nextOf(r, key, change)).plan();
    }

    static void run() throws Exception {
        section("hierarchy: mutation and propagation");
        String rename = "{\"states\": {\"todo\": {\"name\": \"Ready\"}}}";

        Hierarchy.Registry r1 = HierarchyCases.shipped();
        r1.follow.put("acme", "auto");
        Hierarchy.Workspace plat = ws("PLAT", "acme-platform", "{\"states\": {\"in-progress\": {\"wipLimit\": 4}}}", true, Map.of("states.todo", 3));
        Hierarchy.Workspace kan = ws("KAN", "kanban", "{}", true, Map.of());
        Hierarchy.Workspace gate = ws("GATE", "stage-gate", "{}", true, Map.of());
        List<String> p1 = publishAndPropagate(r1, List.of(plat, kan, gate), "base", rename);
        check("P1", "a change at the root reaches a workspace four levels down, through the release build and then automatic edges; no delta on the way is rewritten",
                plat.version == 2 && is(name(eff(r1, plat), "todo"), "Ready") && is(name(eff(r1, kan), "todo"), "Ready")
                        && plat.delta.equals(h("instance-platform.json"))
                        && Hierarchy.sections(r1.get("acme", 2).document()).equals(Hierarchy.sections(h("acme.v1.json")))
                        && Hierarchy.sections(r1.get("scrum", 2).document()).equals(Hierarchy.sections(h("scrum.v1.json")))
                        && r1.latest("hybrid") == 2 && r1.latest("acme-platform") == 2 && r1.latest("stage-gate") == 2
                        && !states(eff(r1, gate)).containsKey("todo")
                        && has(p1, "workspace PLAT: acme-platform@1 -> acme-platform@2, revision 1, configuration.changed cause ancestor base@2 [UPDATE todo name \"To Do\" -> \"Ready\"]"),
                p1);
        check("P2", "one configuration.changed per re-pinned workspace, each naming the version originally published; none for a workspace left behind",
                p1.stream().filter(l -> l.contains("configuration.changed")).count() == 3
                        && p1.stream().filter(l -> l.contains("configuration.changed")).allMatch(l -> l.contains("cause ancestor base@2"))
                        && plat.revision == 1 && kan.revision == 1,
                p1);

        Hierarchy.Registry r3 = HierarchyCases.shipped();
        r3.follow.put("acme", "auto");
        Hierarchy.Workspace plat3 = ws("PLAT", "acme-platform", "{}", true, Map.of());
        Hierarchy.Workspace scr = ws("SCR", "scrum", "{}", true, Map.of());
        List<String> p3 = publishAndPropagate(r3, List.of(plat3, scr), "scrum", "{\"states\": {\"in-review\": {\"name\": \"Peer Review\"}}}");
        check("P3", "nearest level wins, field by field: Scrum renames In Review; Acme had renamed it, so Acme's teams keep Code Review; plain Scrum workspaces follow",
                is(name(eff(r3, scr), "in-review"), "Peer Review") && is(name(eff(r3, plat3), "in-review"), "Code Review")
                        && is(name(r3.resolved.get("hybrid@2"), "in-review"), "Peer Review") && plat3.version == 2,
                p3);

        Hierarchy.Registry r4 = HierarchyCases.shipped();
        Hierarchy.Workspace plat4 = ws("PLAT", "acme-platform", "{}", true, Map.of());
        List<String> p4 = publishAndPropagate(r4, List.of(plat4), "base", rename);
        Resolver.Upgrade later = Propagation.upgradeEdge(r4, Hierarchy.sections(h("acme.v1.json")), "scrum@1", "scrum@2", Map.of());
        check("P4", "a manual edge stops propagation: Acme stays on scrum@1 and is shown the dry run; nothing below it moves; it upgrades later, explicitly",
                r4.latest("scrum") == 2 && r4.latest("acme") == 1 && r4.latest("acme-platform") == 1
                        && has(p4, "template acme@1: stays on scrum@1, manual") && !has(p4, "workspace PLAT")
                        && plat4.version == 1 && plat4.revision == 0 && is(name(eff(r4, plat4), "todo"), "To Do")
                        && later.conflicts().isEmpty() && is(name(later.effective(), "todo"), "Ready"),
                p4);

        Hierarchy.Registry r5 = HierarchyCases.shipped();
        r5.follow.put("acme", "auto");
        Hierarchy.Workspace busy = ws("S1", "scrum", "{}", true, Map.of("states.in-review", 4));
        Hierarchy.Workspace idle = ws("S2", "scrum", "{}", true, Map.of());
        Hierarchy.Workspace deep = ws("PLAT", "acme-platform", "{}", true, Map.of("states.in-review", 4));
        List<String> p5 = publishAndPropagate(r5, List.of(busy, idle, deep), "scrum", "{\"states\": {\"in-review\": null}}");
        check("P5", "occupancy at the next level: Scrum drops In Review; a busy workspace is refused and stays, an idle one deletes the column; Acme had customised it, so it is promoted into acme@2 and Acme's busy team is untouched",
                has(p5, "workspace S1: blocked, stays on scrum@1: [states.in-review: still used by 4") && busy.version == 1 && busy.revision == 0
                        && idle.version == 2 && has(p5, "workspace S2: scrum@1 -> scrum@2, revision 1, configuration.changed cause ancestor scrum@2 [DELETE in-review, UPDATE done ordinal 4 -> 3, UPDATE no-go ordinal 5 -> 4]")
                        && is(Json.obj(Json.obj(r5.get("acme", 2).document().get("states")).get("in-review")),
                        Json.parse("{\"name\": \"Code Review\", \"category\": \"IN_PROGRESS\", \"after\": \"in-progress\"}"))
                        && deep.version == 2 && has(p5, "workspace PLAT: acme-platform@1 -> acme-platform@2, revision 1, configuration.changed cause ancestor scrum@2 []")
                        && is(state(r5.resolved.get("hybrid@2"), "release-review").get("after"), "in-progress"),
                p5);

        Hierarchy.Registry r6 = HierarchyCases.shipped();
        r6.follow.put("acme", "auto");
        Hierarchy.Workspace far = ws("PLAT", "acme-platform", "{}", true, Map.of("states.todo", 3));
        Hierarchy.Workspace near = ws("PLAT2", "acme-platform", "{}", true, Map.of());
        List<String> p6 = publishAndPropagate(r6, List.of(far, near), "base",
                "{\"states\": {\"todo\": null, \"ready\": {\"name\": \"Ready\", \"category\": \"START_STATE\"}}, \"stateOrder\": [\"ready\", \"in-progress\", \"done\", \"no-go\"]}");
        check("P6", "occupancy four levels down: Base drops To Do, which no level customised; every template level rebases, the busy workspace is refused, its idle sibling deletes the column",
                r6.latest("acme-platform") == 2 && far.version == 1 && has(p6, "workspace PLAT: blocked, stays on acme-platform@1: [states.todo: still used by 3")
                        && near.version == 2 && has(p6, "workspace PLAT2: acme-platform@1 -> acme-platform@2")
                        && is(state(r6.resolved.get("scrum@2"), "backlog").get("before"), "in-progress")
                        && order(eff(r6, near)).equals(List.of("ready", "backlog", "refined", "in-progress", "in-review", "qa", "done", "no-go")),
                p6);

        Hierarchy.Registry r7 = HierarchyCases.shipped();
        Hierarchy.publish(r7, "ops", HierarchyCases.derived("ops", 1, "scrum", 1,
                "\"states\": {\"blocked\": {\"name\": \"Blocked\", \"category\": \"START_STATE\", \"after\": \"backlog\"}}"));
        r7.follow.put("ops", "auto");
        Hierarchy.Workspace ops = ws("OPS", "ops", "{}", true, Map.of());
        List<String> p7 = publishAndPropagate(r7, List.of(ops), "scrum",
                "{\"states\": {\"blocked\": {\"name\": \"Blocked\", \"category\": \"IN_PROGRESS\", \"after\": \"in-review\"}}}");
        check("P7", "a conflict at a template edge: Ops had added Blocked as a start state, Scrum adds it in progress; Ops is refused and stays with its workspaces, Scrum's other children move on",
                has(p7, "template ops@1: blocked, stays on scrum@1: [states.blocked: the instance added it as START_STATE, the template now defines it as IN_PROGRESS")
                        && r7.latest("ops") == 1 && ops.version == 1 && r7.latest("hybrid") == 2,
                p7);

        Hierarchy.Registry r8 = HierarchyCases.shipped();
        Hierarchy.Workspace onScrum = ws("SCR", "scrum", "{\"states\": {\"in-review\": {\"wipLimit\": 2}}}", true, Map.of());
        List<String> p8 = publishAndPropagate(r8, List.of(), "scrum", "{\"locks\": [\"states.in-review\"]}");
        Resolver.Upgrade lockedOut = Propagation.upgradeEdge(r8, Hierarchy.sections(h("acme.v1.json")), "scrum@1", "scrum@2", Map.of());
        Resolver.Upgrade wsLocked = Propagation.upgradeEdge(r8, onScrum.delta, "scrum@1", "scrum@2", Map.of());
        check("P8", "a parent that adds a lock on a path a child overrides refuses the child's upgrade, at a template edge and at a workspace",
                has(lockedOut.conflicts(), "states.in-review.name: locked by an ancestor template ('states.in-review')")
                        && has(wsLocked.conflicts(), "states.in-review.wipLimit: locked") && r8.latest("hybrid") == 2,
                lockedOut.conflicts() + " " + p8);

        Hierarchy.Registry r9 = HierarchyCases.shipped();
        Hierarchy.Workspace manual = ws("W", "scrum", "{\"states\": {\"refined\": {\"name\": \"Refined\", \"category\": \"START_STATE\", \"after\": \"backlog\"}}}", false, Map.of());
        List<String> p9 = new ArrayList<>(publishAndPropagate(r9, List.of(manual), "scrum", "{\"states\": {\"todo\": {\"name\": \"Next\"}}}"));
        p9.addAll(publishAndPropagate(r9, List.of(manual), "scrum", "{\"states\": {\"blocked\": {\"name\": \"Blocked\", \"category\": \"IN_PROGRESS\", \"after\": \"in-review\"}}}"));
        Resolver.Upgrade skip = Propagation.upgradeEdge(r9, manual.delta, "scrum@1", "scrum@3", Map.of());
        Resolver.Upgrade step1 = Propagation.upgradeEdge(r9, manual.delta, "scrum@1", "scrum@2", Map.of());
        Resolver.Upgrade step2 = Propagation.upgradeEdge(r9, step1.delta(), "scrum@2", "scrum@3", Map.of());
        Hierarchy.Registry r9b = HierarchyCases.shipped();
        Hierarchy.Workspace cr = ws("CR", "scrum", "{\"states\": {\"in-review\": {\"name\": \"CR\"}}}", false, Map.of());
        publishAndPropagate(r9b, List.of(), "scrum", "{\"states\": {\"in-review\": null}}");
        publishAndPropagate(r9b, List.of(), "scrum", "{\"states\": {\"in-review\": {\"name\": \"Peer Review\", \"category\": \"IN_PROGRESS\", \"after\": \"todo\"}}}");
        Resolver.Upgrade crSkip = Propagation.upgradeEdge(r9b, cr.delta, "scrum@1", "scrum@3", Map.of());
        Resolver.Upgrade crStep = Propagation.upgradeEdge(r9b, Propagation.upgradeEdge(r9b, cr.delta, "scrum@1", "scrum@2", Map.of()).delta(), "scrum@2", "scrum@3", Map.of());
        check("P9", "a manual workspace sees each publish as a preview and may skip versions; a skip equals the stepwise path here, but can differ: promoted at v2 and re-added at v3, stepwise keeps the instance's place, the skip takes the template's",
                p9.stream().filter(l -> l.startsWith("workspace W: stays on scrum@1, manual")).count() == 2 && manual.version == 1
                        && skip.conflicts().isEmpty() && is(name(skip.effective(), "todo"), "Next")
                        && order(skip.effective()).equals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "blocked", "done", "no-go"))
                        && step2.conflicts().isEmpty() && step2.effective().equals(skip.effective()) && step2.delta().equals(skip.delta())
                        && crSkip.conflicts().isEmpty() && crStep.conflicts().isEmpty()
                        && is(name(crSkip.effective(), "in-review"), "CR") && is(name(crStep.effective(), "in-review"), "CR")
                        && order(crStep.effective()).indexOf("in-review") == order(crStep.effective()).indexOf("in-progress") + 1
                        && order(crSkip.effective()).indexOf("in-review") == order(crSkip.effective()).indexOf("todo") + 1,
                p9 + " " + skip + " | " + order(crSkip.effective()) + " " + order(crStep.effective()));

        Hierarchy.Registry r10 = HierarchyCases.shipped();
        Hierarchy.publish(r10, Hierarchy.SYSTEM, nextOf(r10, "scrum", rename));
        Hierarchy.Workspace late = ws("LATE", "scrum", "{}", true, Map.of());
        Hierarchy.Workspace raced = ws("RACED", "scrum", "{}", true, Map.of());
        Propagation.Planned lp = Propagation.plan(r10, late, "scrum@2");
        Propagation.Planned rp = Propagation.plan(r10, raced, "scrum@2");
        late.delta = patch("{\"states\": {\"in-review\": {\"wipLimit\": 2}}}");
        late.revision++;
        raced.version = 2;
        String lateLine = Propagation.apply(r10, lp, "scrum@2", "scrum@2");
        String racedLine = Propagation.apply(r10, rp, "scrum@2", "scrum@2");
        check("P10", "apply recomputes in the workspace's own transaction from its current pin and delta: a delta written after the plan is kept, not overwritten by the plan's preview; a workspace already at or past the target is skipped",
                late.version == 2 && is(state(eff(r10, late), "in-review").get("wipLimit"), 2L) && is(name(eff(r10, late), "todo"), "Ready")
                        && lateLine.contains("re-planned from revision 1 (the plan saw 0)") && !lp.preview().delta().containsKey("states")
                        && racedLine.equals("workspace RACED: skipped, already on scrum@2, at or past scrum@2") && raced.revision == 0,
                lateLine + " | " + racedLine);
        Hierarchy.Registry r11 = HierarchyCases.shipped();
        Hierarchy.publish(r11, "ops", HierarchyCases.derived("ops", 1, "base", 1, ""));
        r11.follow.put("ops", "auto");
        Hierarchy.publish(r11, Hierarchy.SYSTEM, nextOf(r11, "base", rename));
        Hierarchy.publish(r11, Hierarchy.SYSTEM, nextOf(r11, "base", "{\"states\": {\"todo\": {\"name\": \"Queued\"}}}"));
        List<String> late3 = new ArrayList<>(Propagation.propagate(r11, List.of(), "base", 3, "base@3"));
        late3.addAll(Propagation.propagate(r11, List.of(), "base", 2, "base@2"));
        check("P11", "two root versions in quick succession, propagated out of order: children are discovered as each level is processed and only an older pin is upgraded, so ops lands once on base@3 and is never moved back to base@2",
                r11.latest("ops") == 2 && is(r11.get("ops", 2).parent(), "base@3") && late3.stream().filter(l -> l.contains("ops@")).toList().equals(List.of("template ops@1 -> ops@2, rebased onto base@3"))
                        && is(name(r11.resolved.get("ops@2"), "todo"), "Queued"),
                late3);

        Hierarchy.publish(r10, Hierarchy.SYSTEM, nextOf(r10, "scrum", "{\"states\": {\"in-review\": {\"name\": \"Peer Review\"}}}"));
        Hierarchy.Workspace both = ws("BOTH", "scrum", "{}", true, Map.of());
        Propagation.Planned to2 = Propagation.plan(r10, both, "scrum@2");
        Propagation.Planned to3 = Propagation.plan(r10, both, "scrum@3");
        String line2 = Propagation.apply(r10, to2, "scrum@2", "scrum@2");
        String line3 = Propagation.apply(r10, to3, "scrum@3", "scrum@3");
        String again = Propagation.apply(r10, to3, "scrum@3", "scrum@3");
        check("P12", "two publishes planned from scrum@1 and applied in order: the second is recomputed from scrum@2, so the automatic workspace ends on scrum@3, not stranded on scrum@2; replaying a row is a no-op",
                line2.startsWith("workspace BOTH: scrum@1 -> scrum@2") && line3.startsWith("workspace BOTH: scrum@2 -> scrum@3, revision 2, re-planned from scrum@2 (the plan saw scrum@1)")
                        && both.version == 3 && both.revision == 2 && is(name(eff(r10, both), "in-review"), "Peer Review") && is(name(eff(r10, both), "todo"), "Ready")
                        && again.startsWith("workspace BOTH: skipped"),
                lateLine + " | " + racedLine + " | " + line2 + " | " + line3);
    }
}
