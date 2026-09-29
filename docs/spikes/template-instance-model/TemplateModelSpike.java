// Template -> instance model: resolution prototype and executable evolution cases (spike).
//
// Run from the repository root, on JDK 22+ (multi-file source launch, JEP 458):
//
//     java docs/spikes/template-instance-model/TemplateModelSpike.java
//
// Loads the two built-in templates and the two instance deltas from the .json files next
// to this source, prints the worked example (effective configuration, reviewable override
// list, board_column projection), then runs every instance-edit and template-evolution
// case as an assertion. Exit status is non-zero if any case fails.

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class TemplateModelSpike {

    static Path dir;
    static int passed;
    static int failed;

    public static void main(String[] args) throws Exception {
        dir = Path.of(args.length > 0 ? args[0] : "docs/spikes/template-instance-model");
        Map<String, Object> kanban = load("kanban.v1.json");
        Map<String, Object> scrum = load("scrum.v1.json");
        Map<String, Object> refined = load("instance-refined.json");
        Map<String, Object> review = load("instance-review.json");
        Map<String, Object> none = Map.of();
        Map<String, Integer> empty = Map.of();

        // ---- worked example ------------------------------------------
        Resolver.Resolution worked = Resolver.resolve(scrum, refined);
        System.out.println("== effective configuration: scrum v1 + instance-refined.json");
        System.out.print(Json.write(worked.effective()));
        System.out.println("== overrides (the reviewable diff surface)");
        Resolver.overrides(scrum, refined).forEach(o -> System.out.println("  " + o));
        System.out.println("== board_column projection: freshly provisioned scrum -> instance");
        Resolver.projection(eff(scrum, none), worked.effective()).forEach(op -> System.out.println("  " + op));
        Resolver.Resolution second = Resolver.resolve(scrum, review);
        System.out.println("== overrides: scrum v1 + instance-review.json");
        Resolver.overrides(scrum, review).forEach(o -> System.out.println("  " + o));
        System.out.println("== board_column projection: freshly provisioned scrum -> instance-review");
        Resolver.projection(eff(scrum, none), second.effective()).forEach(op -> System.out.println("  " + op));
        System.out.println();

        // ---- sanity --------------------------------------------------
        section("sanity");
        for (String f : List.of("kanban.v1.json", "scrum.v1.json", "instance-refined.json", "instance-review.json")) {
            String bytes = Files.readString(dir.resolve(f));
            check("S1", f + " is in canonical form", Json.write(Json.parse(bytes)).equals(bytes), f);
        }
        check("S2", "kanban v1 and scrum v1 resolve cleanly with an empty delta",
                Resolver.resolve(kanban, none).ok() && Resolver.resolve(scrum, none).ok(),
                Resolver.resolve(kanban, none).violations() + " " + Resolver.resolve(scrum, none).violations());
        check("S3", "the Refined instance resolves cleanly: Refined between Backlog and To Do, no stateOrder override",
                worked.ok() && !refined.containsKey("stateOrder")
                        && order(worked.effective()).equals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "done"))
                        && is(state(worked.effective(), "refined").get("category"), "NOT_STARTED")
                        && Resolver.writeDelta(scrum, none, refined, empty).ok(),
                worked);
        check("S4", "the review instance resolves cleanly, QA after the renamed review column, bug removed",
                second.ok()
                        && order(second.effective()).equals(List.of("backlog", "todo", "in-progress", "in-review", "qa", "done"))
                        && is(name(second.effective(), "in-review"), "Code Review")
                        && !types(second.effective()).containsKey("bug")
                        && is(state(second.effective(), "done").get("enterFrom"), List.of("qa"))
                        && Resolver.writeDelta(scrum, none, review, empty).ok(),
                second);

        // ---- instance-side edits (delta writes) -----------------------
        section("instance edits");
        Map<String, Object> rename = patch("{\"states\": {\"in-review\": {\"name\": \"Code Review\"}}}");
        check("I1", "rename a state: UPDATE of the same board_column row, items do not move",
                Resolver.projection(eff(scrum, none), eff(scrum, rename))
                        .equals(List.of("UPDATE in-review name \"In Review\" -> \"Code Review\"")),
                Resolver.projection(eff(scrum, none), eff(scrum, rename)));
        Map<String, Object> addUnplaced = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\"}}}");
        check("I2", "add a state: unplaced goes last; placed with after lands right after its anchor",
                order(eff(scrum, addUnplaced)).getLast().equals("qa")
                        && order(worked.effective()).indexOf("refined") == order(worked.effective()).indexOf("backlog") + 1
                        && order(second.effective()).indexOf("qa") == order(second.effective()).indexOf("in-review") + 1,
                order(eff(scrum, addUnplaced)));
        Map<String, Object> dropBacklog = patch("{\"states\": {\"backlog\": null}}");
        check("I3", "remove an empty state: allowed, projects to DELETE",
                Resolver.writeDelta(scrum, none, dropBacklog, empty).ok()
                        && Resolver.projection(eff(scrum, none), eff(scrum, dropBacklog)).contains("DELETE backlog"),
                Resolver.writeDelta(scrum, none, dropBacklog, empty).violations());
        check("I4", "remove an occupied state: rejected",
                has(Resolver.writeDelta(scrum, none, dropBacklog, Map.of("states.backlog", 12)).violations(),
                        "states.backlog: still used by 12"),
                Resolver.writeDelta(scrum, none, dropBacklog, Map.of("states.backlog", 12)).violations());
        Map<String, Object> reorder = patch("{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\"]}");
        check("I5", "reorder states: order replaced whole, projects to ordinal UPDATEs only",
                order(eff(scrum, reorder)).equals(List.of("todo", "backlog", "in-progress", "in-review", "done"))
                        && Resolver.projection(eff(scrum, none), eff(scrum, reorder))
                        .equals(List.of("UPDATE todo ordinal 1 -> 0", "UPDATE backlog ordinal 0 -> 1")),
                Resolver.projection(eff(scrum, none), eff(scrum, reorder)));
        check("I6", "remove an item type still used by items: rejected",
                has(Resolver.writeDelta(scrum, none, review, Map.of("itemTypes.bug", 3)).violations(),
                        "itemTypes.bug: still used by 3"),
                Resolver.writeDelta(scrum, none, review, Map.of("itemTypes.bug", 3)).violations());
        check("I7", "remove the default item type without re-pointing the default: rejected; re-pointed: allowed",
                has(Resolver.resolve(scrum, patch("{\"itemTypes\": {\"story\": null}}")).violations(),
                        "settings.defaultItemType")
                        && Resolver.resolve(scrum, patch(
                        "{\"itemTypes\": {\"story\": null}, \"settings\": {\"defaultItemType\": \"task\"}}")).ok(),
                Resolver.resolve(scrum, patch("{\"itemTypes\": {\"story\": null}}")).violations());
        check("I8", "delta outside its sections, tombstone of an unknown key, incomplete new state: rejected",
                has(Resolver.resolve(scrum, patch("{\"name\": \"Mine\"}")).violations(), "may not set 'name'")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"nope\": null}}")).violations(),
                        "does not have")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"qa\": {\"name\": \"QA\"}}}")).violations(),
                        "states.qa.category"),
                "");
        Map<String, Object> dangling = patch(
                "{\"states\": {\"in-review\": null, \"done\": {\"enterFrom\": [\"in-review\"]}}}");
        check("I9", "remove a state another state's policy references: rejected",
                has(Resolver.resolve(scrum, dangling).violations(), "enterFrom: references unknown state 'in-review'"),
                Resolver.resolve(scrum, dangling).violations());
        check("I10", "stateOrder listing an unknown or duplicate state: rejected",
                has(Resolver.writeDelta(scrum, none, patch("{\"stateOrder\": [\"ghost\"]}"), empty).violations(),
                        "unknown state 'ghost'")
                        && has(Resolver.resolve(scrum, patch("{\"stateOrder\": [\"todo\", \"todo\"]}")).violations(),
                        "twice"),
                "");
        String x = "\"category\": \"NOT_STARTED\"";
        check("I11", "after naming a removed state, a cycle, two states after one anchor, or also listed in stateOrder: rejected",
                has(Resolver.resolve(scrum, patch("{\"states\": {\"backlog\": null, \"refined\": {\"name\": \"R\", "
                        + x + ", \"after\": \"backlog\"}}}")).violations(), "refined.after: references unknown state 'backlog'")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"b\"},"
                        + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"a\"}}}")).violations(), "placement cycle")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"todo\"},"
                        + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"todo\"}}}")).violations(), "already placed after 'todo'")
                        && has(Resolver.resolve(scrum, Json.obj(Json.mergePatch(refined, patch("{\"stateOrder\": [\"backlog\","
                        + " \"refined\", \"todo\", \"in-progress\", \"in-review\", \"done\"]}")))).violations(), "placed by states.refined.after"),
                "");

        // ---- the Refined column under template evolution ------------------
        section("template evolution: the instance-added Refined column");
        check("R1", "worked instance: the delta is one element, the diff one line, the board one INSERT plus ordinal shifts",
                refined.size() == 1 && Resolver.overrides(scrum, refined).size() == 1
                        && Resolver.projection(eff(scrum, none), worked.effective()).equals(List.of(
                        "INSERT refined name=\"Refined\" ordinal=1", "UPDATE todo ordinal 1 -> 2",
                        "UPDATE in-progress ordinal 2 -> 3", "UPDATE in-review ordinal 3 -> 4", "UPDATE done ordinal 4 -> 5")),
                Resolver.projection(eff(scrum, none), worked.effective()));
        Map<String, Object> addBlocked = next(scrum,
                "{\"states\": {\"blocked\": {\"name\": \"Blocked\", \"category\": \"IN_PROGRESS\"}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"blocked\", \"in-review\", \"done\"]}");
        Resolver.Upgrade r2 = Resolver.upgrade(scrum, addBlocked, refined, empty);
        check("R2", "template adds a column elsewhere: inherited in place, Refined still after Backlog, delta untouched",
                r2.conflicts().isEmpty() && r2.delta().equals(refined) && order(r2.effective()).equals(
                        List.of("backlog", "refined", "todo", "in-progress", "blocked", "in-review", "done")),
                order(r2.effective()));
        Map<String, Object> reordered = next(scrum,
                "{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\"]}");
        Resolver.Upgrade r3 = Resolver.upgrade(scrum, reordered, refined, empty);
        check("R3", "template reorders columns: the new order is inherited and Refined moves with Backlog",
                r3.conflicts().isEmpty() && r3.delta().equals(refined) && order(r3.effective()).equals(
                        List.of("todo", "backlog", "refined", "in-progress", "in-review", "done")),
                order(r3.effective()));
        Map<String, Object> addTriage = next(scrum,
                "{\"states\": {\"triage\": {\"name\": \"Triage\", \"category\": \"NOT_STARTED\"}},"
                        + " \"stateOrder\": [\"backlog\", \"triage\", \"todo\", \"in-progress\", \"in-review\", \"done\"]}");
        Resolver.Upgrade r4 = Resolver.upgrade(scrum, addTriage, refined, empty);
        check("R4", "template inserts a column right after Backlog: Refined stays next to its anchor, the new one follows",
                r4.conflicts().isEmpty() && order(r4.effective()).equals(
                        List.of("backlog", "refined", "triage", "todo", "in-progress", "in-review", "done")),
                order(r4.effective()));
        Map<String, Object> addReady = next(scrum,
                "{\"states\": {\"refined\": {\"name\": \"Ready\", \"category\": \"NOT_STARTED\"}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"refined\", \"in-progress\", \"in-review\", \"done\"]}");
        Resolver.Upgrade r5 = Resolver.upgrade(scrum, addReady, refined, empty);
        check("R5", "template adds the same key itself (named Ready, placed elsewhere): adopted, instance name and place win",
                r5.conflicts().isEmpty() && is(name(r5.effective(), "refined"), "Refined")
                        && order(r5.effective()).equals(order(worked.effective()))
                        && has(r5.notes(), "states.refined: template now defines it too"),
                r5);
        Map<String, Object> noBacklog = next(scrum,
                "{\"states\": {\"backlog\": null}, \"stateOrder\": [\"todo\", \"in-progress\", \"in-review\", \"done\"]}");
        Resolver.Upgrade r6a = Resolver.upgrade(scrum, noBacklog, refined, Map.of("states.backlog", 30));
        Resolver.Upgrade r6b = Resolver.upgrade(scrum, noBacklog, refined, empty);
        check("R6", "template removes the anchor (Backlog): refused while it holds items; empty, Refined stays first",
                has(r6a.conflicts(), "states.backlog: still used by 30")
                        && r6b.conflicts().isEmpty()
                        && order(r6b.effective()).equals(List.of("refined", "todo", "in-progress", "in-review", "done"))
                        && !state(r6b.effective(), "refined").containsKey("after"),
                r6a.conflicts() + " " + r6b);
        Map<String, Object> dropReview = next(scrum,
                "{\"states\": {\"in-review\": null}, \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"done\"]}");
        Map<String, Object> qaAfterReview = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\","
                + " \"after\": \"in-review\"}}}");
        Resolver.Upgrade r7 = Resolver.upgrade(scrum, dropReview, qaAfterReview, empty);
        check("R7", "template removes a mid-board anchor: the added state is re-anchored to its surviving predecessor",
                r7.conflicts().isEmpty() && is(state(r7.effective(), "qa").get("after"), "in-progress")
                        && order(r7.effective()).equals(List.of("backlog", "todo", "in-progress", "qa", "done")),
                r7);
        Map<String, String> vault = Map.of("TO DO", "backlog", "REFINED", "refined", "PLANNED", "todo",
                "IN PROGRESS", "in-progress", "IN REVIEW", "in-review", "DONE", "done", "NO GO", "done");
        check("R8", "every backlog status maps to a state key of the Refined instance; plain Scrum lacks 'refined'",
                states(worked.effective()).keySet().containsAll(vault.values())
                        && !states(eff(scrum, none)).containsKey("refined"),
                vault);

        // ---- template evolution: states (columns) --------------------
        section("template evolution: states");
        Resolver.Upgrade c1 = Resolver.upgrade(scrum, addBlocked, none, empty);
        check("C1", "template adds a state; instance untouched: inherited at the template's position",
                c1.conflicts().isEmpty()
                        && order(c1.effective()).equals(List.of("backlog", "todo", "in-progress", "blocked", "in-review", "done")),
                order(c1.effective()));
        Resolver.Upgrade c2 = Resolver.upgrade(scrum, addBlocked, reorder, empty);
        check("C2", "template adds a state; instance reordered: inherited, placed after its template predecessor",
                c2.conflicts().isEmpty() && c2.delta().equals(reorder)
                        && order(c2.effective()).equals(List.of("todo", "backlog", "in-progress", "blocked", "in-review", "done")),
                order(c2.effective()));
        Map<String, Object> addQa = next(scrum,
                "{\"states\": {\"qa\": {\"name\": \"Testing\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"in-review\"]}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"qa\", \"done\"]}");
        Resolver.Upgrade c3 = Resolver.upgrade(scrum, addQa, review, empty);
        check("C3", "template adds a state the instance had already added under that key: adopted, instance values win",
                c3.conflicts().isEmpty() && is(name(c3.effective(), "qa"), "QA")
                        && is(state(c3.effective(), "qa").get("wipLimit"), 3L)
                        && is(state(c3.effective(), "qa").get("enterFrom"), List.of("in-review"))
                        && has(c3.notes(), "states.qa: template now defines it too"),
                state(c3.effective(), "qa") + " " + c3.notes());
        Map<String, Object> renames = next(scrum,
                "{\"states\": {\"in-progress\": {\"name\": \"Doing\"}, \"in-review\": {\"name\": \"Peer Review\"},"
                        + " \"backlog\": {\"name\": \"Icebox\"}}}");
        check("C4", "template renames a state; instance untouched: inherits the new name",
                is(name(Resolver.upgrade(scrum, renames, none, empty).effective(), "in-progress"), "Doing"), "");
        Resolver.Upgrade c5 = Resolver.upgrade(scrum, renames, review, empty);
        check("C5", "template renames a state; instance renamed it too: instance name kept, other renames inherited",
                is(name(c5.effective(), "in-review"), "Code Review") && is(name(c5.effective(), "in-progress"), "Doing"),
                c5.effective().get("states"));
        Resolver.Upgrade c6 = Resolver.upgrade(scrum, renames, dropBacklog, empty);
        check("C6", "template renames a state; instance deleted it: stays deleted",
                c6.conflicts().isEmpty() && !states(c6.effective()).containsKey("backlog") && c6.delta().equals(dropBacklog),
                c6);
        Resolver.Upgrade c7 = Resolver.upgrade(scrum, dropReview, none, empty);
        check("C7", "template removes a state; instance untouched, state empty: removed, projects to DELETE",
                c7.conflicts().isEmpty() && !states(c7.effective()).containsKey("in-review")
                        && Resolver.projection(eff(scrum, none), c7.effective()).getFirst().equals("DELETE in-review"),
                c7);
        Resolver.Upgrade c8 = Resolver.upgrade(scrum, dropReview, none, Map.of("states.in-review", 4));
        check("C8", "template removes a state; instance untouched, items in it: upgrade refused",
                has(c8.conflicts(), "states.in-review: still used by 4"), c8.conflicts());
        Resolver.Upgrade c9 = Resolver.upgrade(scrum, dropReview, review, Map.of("states.in-review", 4));
        check("C9", "template removes a state; instance renamed it: promoted to the instance's own, kept in place",
                c9.conflicts().isEmpty() && is(name(c9.effective(), "in-review"), "Code Review")
                        && is(state(c9.effective(), "in-review").get("category"), "IN_PROGRESS")
                        && is(state(c9.effective(), "in-review").get("after"), "in-progress")
                        && order(c9.effective()).equals(order(second.effective()))
                        && !c9.delta().containsKey("stateOrder")
                        && has(c9.notes(), "keeps it as its own"),
                c9);
        Map<String, Object> tombReview = patch("{\"states\": {\"in-review\": null}}");
        Resolver.Upgrade c10 = Resolver.upgrade(scrum, dropReview, tombReview, empty);
        check("C10", "template removes a state; instance deleted it: redundant tombstone dropped, delta now empty",
                c10.conflicts().isEmpty() && c10.delta().isEmpty(), c10.delta());
        check("C11", "template reorders states; instance untouched: inherits the new order",
                order(Resolver.upgrade(scrum, reordered, none, empty).effective()).getFirst().equals("todo"), "");
        Map<String, Object> ownOrder = patch("{\"stateOrder\": [\"backlog\", \"todo\", \"in-review\", \"in-progress\", \"done\"]}");
        Resolver.Upgrade c12 = Resolver.upgrade(scrum, reordered, ownOrder, empty);
        check("C12", "template reorders states; instance reordered too: instance order kept, and the upgrade says so",
                order(c12.effective()).equals(order(eff(scrum, ownOrder)))
                        && has(c12.notes(), "template reordered its states; the instance keeps its own order"),
                c12);
        Resolver.Upgrade c13 = Resolver.upgrade(scrum, dropReview,
                patch("{\"states\": {\"done\": {\"enterFrom\": [\"in-review\"]}}}"), empty);
        check("C13", "template removes a state an instance policy still references: upgrade refused",
                has(c13.conflicts(), "states.done.enterFrom: references unknown state 'in-review'"), c13.conflicts());

        // ---- template evolution: item types -------------------------
        section("template evolution: item types");
        Map<String, Object> addSpike = next(scrum, "{\"itemTypes\": {\"spike\": {\"name\": \"Spike\"}}}");
        check("T1", "template adds an item type; instance untouched: inherited",
                types(Resolver.upgrade(scrum, addSpike, none, empty).effective()).containsKey("spike"), "");
        Resolver.Upgrade t2 = Resolver.upgrade(scrum, addSpike,
                patch("{\"itemTypes\": {\"spike\": {\"name\": \"Research spike\"}}}"), empty);
        check("T2", "template adds an item type the instance had already added: adopted, instance name wins",
                is(Json.obj(types(t2.effective()).get("spike")).get("name"), "Research spike")
                        && has(t2.notes(), "itemTypes.spike: template now defines it too"),
                t2);
        Map<String, Object> dropTask = next(scrum, "{\"itemTypes\": {\"task\": null}}");
        check("T3", "template removes an item type; instance untouched, unused: removed",
                !types(Resolver.upgrade(scrum, dropTask, none, empty).effective()).containsKey("task")
                        && Resolver.upgrade(scrum, dropTask, none, empty).conflicts().isEmpty(), "");
        check("T4", "template removes an item type; instance untouched, items use it: upgrade refused",
                has(Resolver.upgrade(scrum, dropTask, none, Map.of("itemTypes.task", 7)).conflicts(),
                        "itemTypes.task: still used by 7"), "");
        Resolver.Upgrade t5 = Resolver.upgrade(scrum, dropTask,
                patch("{\"itemTypes\": {\"task\": {\"name\": \"Chore\"}}}"), Map.of("itemTypes.task", 7));
        check("T5", "template removes an item type; instance renamed it: promoted, kept",
                t5.conflicts().isEmpty() && is(Json.obj(types(t5.effective()).get("task")).get("name"), "Chore"), t5);
        Map<String, Object> dropBug = next(scrum, "{\"itemTypes\": {\"bug\": null}}");
        Resolver.Upgrade t6 = Resolver.upgrade(scrum, dropBug, review, empty);
        check("T6", "template removes an item type; instance deleted it: redundant tombstone dropped",
                t6.conflicts().isEmpty() && !t6.delta().containsKey("itemTypes") && !types(t6.effective()).containsKey("bug"),
                t6.delta());
        Resolver.Upgrade t7 = Resolver.upgrade(scrum, next(scrum, "{\"itemTypes\": {\"bug\": {\"name\": \"Defect\"}}}"),
                review, empty);
        check("T7", "template renames an item type; instance deleted it: stays deleted",
                !types(t7.effective()).containsKey("bug") && t7.delta().equals(review), t7);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---- helpers ------------------------------------------------------

    static Map<String, Object> load(String file) throws Exception {
        return Json.obj(Json.parse(Files.readString(dir.resolve(file))));
    }

    static Map<String, Object> patch(String json) {
        return Json.obj(Json.parse(json));
    }

    /** The next version of a template, derived from it by a merge patch (templates are authored the same way). */
    static Map<String, Object> next(Map<String, Object> template, String change) {
        Map<String, Object> t = Json.obj(Json.mergePatch(template, patch(change)));
        t.put("version", (Long) template.get("version") + 1);
        return t;
    }

    static Map<String, Object> eff(Map<String, Object> template, Map<String, Object> delta) {
        return Resolver.resolve(template, delta).effective();
    }

    static Map<String, Object> states(Map<String, Object> eff) {
        return Json.obj(eff.get("states"));
    }

    static Map<String, Object> state(Map<String, Object> eff, String key) {
        return Json.obj(states(eff).get(key));
    }

    static Object name(Map<String, Object> eff, String key) {
        return state(eff, key).get("name");
    }

    static List<String> order(Map<String, Object> eff) {
        return Json.strings(eff.get("stateOrder"));
    }

    static Map<String, Object> types(Map<String, Object> eff) {
        return Json.obj(eff.get("itemTypes"));
    }

    /** Null-safe equality, so a broken resolver reports FAIL lines instead of aborting the run. */
    static boolean is(Object actual, Object expected) {
        return java.util.Objects.equals(actual, expected);
    }

    static boolean has(List<String> lines, String fragment) {
        return lines.stream().anyMatch(l -> l.contains(fragment));
    }

    static void section(String title) {
        System.out.println("-- " + title);
    }

    static void check(String id, String what, boolean ok, Object detail) {
        if (ok) {
            passed++;
            System.out.printf("PASS %-4s %s%n", id, what);
        } else {
            failed++;
            System.out.printf("FAIL %-4s %s%n     got: %s%n", id, what, detail);
        }
    }
}
