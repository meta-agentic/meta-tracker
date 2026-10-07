// Cases for the four worked methodology templates (VEC-66): what each contributes beyond its
// states, the validation of those sections, and the workflow definition each compiles to for
// the state-machine library. Called from HierarchyCases.run().

import java.util.List;
import java.util.Map;

final class MethodologyCases extends Cases {

    static Map<String, Object> cadence(Map<String, Object> eff) {
        return Json.obj(eff.get("cadence"));
    }

    static Object parents(Map<String, Object> eff, String type) {
        return Json.obj(types(eff).get(type)).get("parents");
    }

    /** The transitions of a workflow definition, as "from>to" or "from>to[guard]". */
    static List<String> moves(Map<String, Object> definition) {
        return ((List<?>) definition.get("transitions")).stream().map(t -> {
            Map<String, Object> m = Json.obj(t);
            return m.get("from") + ">" + m.get("to") + (m.containsKey("guard") ? "[" + m.get("guard") + "]" : "");
        }).toList();
    }

    static void run(Hierarchy.Registry r) throws Exception {
        section("methodologies");
        Map<String, Object> scrum = r.resolved.get("scrum@1");
        Map<String, Object> kanban = r.resolved.get("kanban@1");
        Map<String, Object> hybrid = r.resolved.get("hybrid@1");
        Map<String, Object> sg = r.resolved.get("stage-gate@1");

        check("M1", "Scrum: two-week sprints, epic > story or bug > task, Fibonacci points, Backlog before To Do",
                is(cadence(scrum).get("mode"), "sprint") && is(cadence(scrum).get("sprintDays"), 14L)
                        && is(parents(scrum, "story"), List.of("epic")) && is(parents(scrum, "task"), List.of("story", "bug"))
                        && parents(scrum, "epic") == null
                        && is(Json.obj(Json.obj(scrum.get("fields")).get("storyPoints")).get("scale"), "fibonacci")
                        && order(scrum).getFirst().equals("backlog"),
                scrum);
        check("M2", "Kanban: continuous flow inherited from the base, a WIP limit on In Progress, no point field, no sprint length",
                is(cadence(kanban).get("mode"), "flow") && !cadence(kanban).containsKey("sprintDays")
                        && is(state(kanban, "in-progress").get("wipLimit"), 5L)
                        && Json.obj(kanban.get("fields")).isEmpty() && is(parents(kanban, "task"), List.of("epic")),
                kanban);
        Map<String, Object> hybridFlow = Workflow.definition(hybrid);
        check("M3", "Hybrid, three levels deep: sprints from Scrum, phase > epic > story, and a release gate that is the only way to Released",
                is(cadence(hybrid).get("mode"), "sprint") && is(cadence(hybrid).get("sprintDays"), 14L)
                        && is(parents(hybrid, "epic"), List.of("phase")) && parents(hybrid, "phase") == null
                        && order(hybrid).equals(List.of("backlog", "todo", "in-progress", "in-review", "release-review", "done", "no-go"))
                        && is(name(hybrid, "done"), "Released") && is(state(hybrid, "done").get("outcome"), "DELIVERED")
                        && moves(hybridFlow).contains("release-review>done[gate:release-review]")
                        && moves(hybridFlow).stream().filter(m -> m.contains(">done")).count() == 1
                        && Hierarchy.chain(r, r.get("hybrid", 1), new java.util.ArrayList<>()).size() == 3,
                moves(hybridFlow));
        Map<String, Object> sgFlow = Workflow.definition(sg);
        check("M4", "Stage-gate: phases not sprints; stages and gates in order; Base's To Do and In Progress removed; project > deliverable > task, milestones",
                is(cadence(sg).get("mode"), "phase") && !cadence(sg).containsKey("sprintDays")
                        && order(sg).equals(List.of("idea", "scoping", "gate-1", "development", "gate-2", "done", "no-go"))
                        && !states(sg).containsKey("todo") && is(name(sg, "done"), "Launched") && is(name(sg, "no-go"), "Killed")
                        && is(state(sg, "gate-1").get("gate"), Json.parse("{\"approvals\": 2}"))
                        && is(parents(sg, "task"), List.of("deliverable")) && is(parents(sg, "milestone"), List.of("project"))
                        && is(Json.obj(sg.get("settings")).get("defaultItemType"), "project")
                        && is(Resolver.fallback(sg, "START_STATE", null), "idea") && is(Resolver.fallback(sg, "END_STATE", "DISCONTINUED"), "no-go"),
                sg);
        List<String> sgMoves = moves(sgFlow);
        check("M5", "workflow definition for the state-machine library: enterFrom becomes transitions, a gate becomes a named guard on every way out of it, ends carry their outcome",
                sgMoves.equals(List.of("idea>scoping", "gate-1>scoping[gate:gate-1]", "scoping>gate-1", "gate-1>development[gate:gate-1]",
                        "gate-2>development[gate:gate-2]", "development>gate-2", "gate-2>done[gate:gate-2]",
                        "idea>no-go", "gate-1>no-go[gate:gate-1]", "gate-2>no-go[gate:gate-2]"))
                        && !sgMoves.contains("idea>development")
                        && is(sgFlow.get("initial"), List.of("idea"))
                        && is(Json.obj(sgFlow.get("states")).get("done"), Json.parse("{\"end\": \"DELIVERED\"}"))
                        && Json.obj(sgFlow.get("states")).keySet().equals(states(sg).keySet())
                        && List.copyOf(sgFlow.keySet()).equals(List.of("states", "initial", "transitions"))
                        && Workflow.definition(Hierarchy.derive(r.resolved.get("base@1"), reversed(HierarchyCases.h("stage-gate.v1.json")), "x").effective()).equals(sgFlow),
                sgMoves);
        Map<String, Object> base = Hierarchy.strip(r.resolved.get("scrum@1"));
        check("M6", "item type hierarchy: an unknown parent, a loop, or removing a type another still names as parent is rejected",
                has(Resolver.resolve(base, patch("{\"itemTypes\": {\"story\": {\"parents\": [\"saga\"]}}}")).violations(), "itemTypes.story.parents: references unknown item type 'saga'")
                        && has(Resolver.resolve(base, patch("{\"itemTypes\": {\"epic\": {\"parents\": [\"task\"]}}}")).violations(), "an item type cannot be its own ancestor")
                        && has(Resolver.resolve(base, patch("{\"itemTypes\": {\"bug\": null}}")).violations(), "itemTypes.task.parents: references unknown item type 'bug'")
                        && Resolver.resolve(base, patch("{\"itemTypes\": {\"bug\": null, \"task\": {\"parents\": [\"story\"]}}}")).ok(),
                Resolver.resolve(base, patch("{\"itemTypes\": {\"epic\": {\"parents\": [\"task\"]}}}")).violations());
        check("M7", "cadence and gates validate: a closed set of modes, a sprint length only with sprints, no gate on an end state, approvals a positive count",
                has(Resolver.resolve(base, patch("{\"cadence\": {\"mode\": \"waterfall\"}}")).violations(), "cadence.mode: must be one of [sprint, flow, phase]")
                        && has(Resolver.resolve(base, patch("{\"cadence\": {\"mode\": \"flow\"}}")).violations(), "cadence.sprintDays: a whole number of days from 1 to 56, and only with mode sprint")
                        && Resolver.resolve(base, patch("{\"cadence\": {\"mode\": \"flow\", \"sprintDays\": null}}")).ok()
                        && has(Resolver.resolve(base, patch("{\"states\": {\"done\": {\"gate\": {\"approvals\": 1}}}}")).violations(), "states.done.gate: an END_STATE state cannot be a gate")
                        && has(Resolver.resolve(base, patch("{\"states\": {\"in-review\": {\"gate\": {\"approvals\": 0}}}}")).violations(), "states.in-review.gate.approvals: must be a positive integer")
                        && has(Resolver.resolve(base, patch("{\"states\": {\"in-review\": {\"gate\": true}}}")).violations(), "states.in-review.gate: must be an object"),
                Resolver.resolve(base, patch("{\"cadence\": {\"mode\": \"flow\"}}")).violations());

        Map<String, Object> narrowed = patch("{\"itemTypes\": {\"task\": {\"parents\": [\"story\"]}}}");
        Map<String, Integer> tasksUnderBugs = Map.of("itemTypes.task.parents.bug", 12);
        Map<String, Object> scrumNarrowed = Json.obj(Json.mergePatch(base, narrowed));
        check("M8", "narrowing an item type's parents is refused while items sit under a parent it drops, at a delta write and at an upgrade; allowed when none do",
                has(Resolver.writeDelta(base, Map.of(), narrowed, tasksUnderBugs).violations(), "itemTypes.task.parents: no longer allows 'bug' while 12 task item(s) sit under one")
                        && Resolver.writeDelta(base, Map.of(), narrowed, Map.of()).ok()
                        && has(Resolver.upgrade(base, scrumNarrowed, Map.of(), tasksUnderBugs).conflicts(), "no longer allows 'bug'")
                        && Resolver.upgrade(base, scrumNarrowed, Map.of(), Map.of()).conflicts().isEmpty(),
                Resolver.writeDelta(base, Map.of(), narrowed, tasksUnderBugs).violations());

        String seq = "\"states\": {\"todo\": null, \"in-progress\": null,"
                + " \"requirements\": {\"name\": \"Requirements\", \"category\": \"START_STATE\", \"enterFrom\": []},"
                + " \"design\": {\"name\": \"Design\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"requirements\"]},"
                + " \"build\": {\"name\": \"Build\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"design\"]},"
                + " \"verify\": {\"name\": \"Verify\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"build\"]GATE},"
                + " \"done\": {\"name\": \"Released\", \"enterFrom\": [\"verify\"]}},"
                + " \"stateOrder\": [\"requirements\", \"design\", \"build\", \"verify\", \"done\", \"no-go\"], \"cadence\": {\"mode\": \"phase\"}";
        Map<String, Object> baseRes = r.resolved.get("base@1");
        Resolver.Resolution waterfall = Hierarchy.derive(baseRes, HierarchyCases.derived("waterfall", 1, "base", 1, seq.replace("GATE", "")), "waterfall@1");
        Resolver.Resolution signedOff = Hierarchy.derive(baseRes, HierarchyCases.derived("waterfall", 1, "base", 1,
                seq.replace("GATE", ", \"gate\": {\"approvals\": 1}") + ", \"settings\": {\"gatedDelivery\": true}"), "waterfall@1");
        List<String> wf = moves(Workflow.definition(waterfall.effective()));
        check("M9", "waterfall maps without a new concept: phase cadence, stages entered strictly in sequence, gates optional (a sign-off gate before release, with gated delivery, also resolves)",
                waterfall.ok() && signedOff.ok() && is(cadence(waterfall.effective()).get("mode"), "phase")
                        && wf.containsAll(List.of("requirements>design", "design>build", "build>verify", "verify>done"))
                        && wf.stream().filter(m -> !m.endsWith(">no-go")).count() == 4
                        && moves(Workflow.definition(signedOff.effective())).contains("verify>done[gate:verify]"),
                waterfall.violations() + " " + wf);

        PropagationCases.run();
    }
}
