// Template -> instance model: resolution prototype and executable evolution cases (spike).
//
// Run from the repository root, on JDK 22+ (multi-file source launch, JEP 458):
//
//     java docs/spikes/template-instance-model/TemplateModelSpike.java
//
// Loads the two built-in templates and the two instance deltas from the .json files next
// to this source, prints the worked example (effective configuration, reviewable override
// list, board_column projection), then runs every instance-edit and template-evolution
// case as an assertion. Exit status is non-zero if any case fails. The assertion helpers
// are in Cases.java.

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class TemplateModelSpike extends Cases {

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
        Diff.overrides(scrum, refined).forEach(o -> System.out.println("  " + o));
        System.out.println("== board_column projection: freshly provisioned scrum -> instance");
        Diff.projection(eff(scrum, none), worked.effective()).forEach(op -> System.out.println("  " + op));
        Resolver.Resolution second = Resolver.resolve(scrum, review);
        System.out.println("== overrides: scrum v1 + instance-review.json");
        Diff.overrides(scrum, review).forEach(o -> System.out.println("  " + o));
        System.out.println("== board_column projection: freshly provisioned scrum -> instance-review");
        Diff.projection(eff(scrum, none), second.effective()).forEach(op -> System.out.println("  " + op));
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
                        && order(worked.effective()).equals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"))
                        && is(state(worked.effective(), "refined").get("category"), "START_STATE")
                        && Resolver.writeDelta(scrum, none, refined, empty).ok(),
                worked);
        check("S4", "the review instance resolves cleanly, QA after the renamed review column, bug removed",
                second.ok()
                        && order(second.effective()).equals(List.of("backlog", "todo", "in-progress", "in-review", "qa", "done", "no-go"))
                        && is(name(second.effective(), "in-review"), "Code Review")
                        && !types(second.effective()).containsKey("bug")
                        && is(state(second.effective(), "done").get("enterFrom"), List.of("qa"))
                        && Resolver.writeDelta(scrum, none, review, empty).ok(),
                second);

        // ---- instance-side edits (delta writes) -----------------------
        section("instance edits");
        Map<String, Object> rename = patch("{\"states\": {\"in-review\": {\"name\": \"Code Review\"}}}");
        check("I1", "rename a state: UPDATE of the same board_column row, items do not move",
                Diff.projection(eff(scrum, none), eff(scrum, rename))
                        .equals(List.of("UPDATE in-review name \"In Review\" -> \"Code Review\"")),
                Diff.projection(eff(scrum, none), eff(scrum, rename)));
        Map<String, Object> addUnplaced = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\"},"
                + " \"uat\": {\"name\": \"UAT\", \"category\": \"IN_PROGRESS\"}}}");
        Map<String, Object> inbox = patch("{\"states\": {\"inbox\": {\"name\": \"Inbox\", \"category\": \"START_STATE\","
                + " \"before\": \"backlog\"}}}");
        Map<String, Object> listedQa = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\"}},"
                + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"qa\", \"in-review\", \"done\", \"no-go\"]}");
        check("I2", "add a state: unplaced is rejected (jsonb keeps no member order); after, before or stateOrder place it",
                has(Resolver.resolve(scrum, addUnplaced).violations(), "states.qa: an added state must be placed")
                        && has(Resolver.resolve(scrum, addUnplaced).violations(), "states.uat: an added state must be placed")
                        && order(worked.effective()).indexOf("refined") == order(worked.effective()).indexOf("backlog") + 1
                        && order(second.effective()).indexOf("qa") == order(second.effective()).indexOf("in-review") + 1
                        && Resolver.resolve(scrum, inbox).ok() && order(eff(scrum, inbox)).getFirst().equals("inbox")
                        && Resolver.resolve(scrum, listedQa).ok() && order(eff(scrum, listedQa)).indexOf("qa") == 3,
                Resolver.resolve(scrum, addUnplaced).violations());
        Map<String, Object> dropBacklog = patch("{\"states\": {\"backlog\": null}}");
        check("I3", "remove an empty state: allowed, projects to DELETE",
                Resolver.writeDelta(scrum, none, dropBacklog, empty).ok()
                        && Diff.projection(eff(scrum, none), eff(scrum, dropBacklog)).contains("DELETE backlog"),
                Resolver.writeDelta(scrum, none, dropBacklog, empty).violations());
        check("I4", "remove an occupied state: rejected",
                has(Resolver.writeDelta(scrum, none, dropBacklog, Map.of("states.backlog", 12)).violations(),
                        "states.backlog: still used by 12"),
                Resolver.writeDelta(scrum, none, dropBacklog, Map.of("states.backlog", 12)).violations());
        Map<String, Object> reorder = patch("{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        check("I5", "reorder states: order replaced whole, projects to ordinal UPDATEs only",
                order(eff(scrum, reorder)).equals(List.of("todo", "backlog", "in-progress", "in-review", "done", "no-go"))
                        && Diff.projection(eff(scrum, none), eff(scrum, reorder))
                        .equals(List.of("UPDATE todo ordinal 1 -> 0", "UPDATE backlog ordinal 0 -> 1")),
                Diff.projection(eff(scrum, none), eff(scrum, reorder)));
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
        check("I8", "delta outside its sections, tombstone of an unknown key, incomplete new state, badly shaped section: rejected",
                has(Resolver.resolve(scrum, patch("{\"name\": \"Mine\"}")).violations(), "may not set 'name'")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"nope\": null}}")).violations(),
                        "does not have")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"after\": \"todo\"}}}"))
                        .violations(), "states.qa.category")
                        && has(Resolver.resolve(scrum, patch("{\"fields\": null}")).violations(), "fields: must be an object; a whole section cannot be removed")
                        && Resolver.resolve(scrum, patch("{\"fields\": null}")).effective().get("fields").equals(scrum.get("fields"))
                        && has(Resolver.resolve(scrum, patch("{\"settings\": \"x\"}")).violations(), "settings: must be an object")
                        && has(Resolver.resolve(scrum, patch("{\"stateOrder\": {\"a\": 1}}")).violations(), "stateOrder: must be an array")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"todo\": 5}}")).violations(), "states.todo: must be an object"),
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
        String x = "\"category\": \"START_STATE\"";
        check("I11", "an anchor naming a removed state, a cycle, two states on one side of an anchor, both anchors, or also listed in stateOrder: rejected",
                has(Resolver.resolve(scrum, patch("{\"states\": {\"backlog\": null, \"refined\": {\"name\": \"R\", "
                        + x + ", \"after\": \"backlog\"}}}")).violations(), "refined.after: references unknown state 'backlog'")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"b\"},"
                        + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"a\"}}}")).violations(), "placement cycle")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"todo\"},"
                        + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"todo\"}}}")).violations(), "already placed after 'todo'")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"before\": \"todo\"},"
                        + " \"b\": {\"name\": \"B\", " + x + ", \"before\": \"todo\"}}}")).violations(), "already placed before 'todo'")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"todo\","
                        + " \"before\": \"done\"}}}")).violations(), "not both")
                        && has(Resolver.resolve(scrum, Json.obj(Json.mergePatch(refined, patch("{\"stateOrder\": [\"backlog\","
                        + " \"refined\", \"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}")))).violations(), "placed by states.refined.after"),
                "");

        Map<String, Object> many = patch("{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"todo\"},"
                + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"a\"}, \"c\": {\"name\": \"C\", \"category\": \"IN_PROGRESS\","
                + " \"before\": \"done\"}}}");
        check("I12", "resolution ignores member order (jsonb keeps none): each delta, members reversed, resolves to the same order and content",
                order(eff(scrum, reversed(refined))).equals(order(worked.effective()))
                        && eff(scrum, reversed(refined)).equals(worked.effective())
                        && order(eff(scrum, reversed(review))).equals(order(second.effective()))
                        && eff(scrum, reversed(review)).equals(second.effective())
                        && Resolver.resolve(scrum, many).ok()
                        && order(eff(scrum, many)).equals(List.of("backlog", "todo", "a", "b", "in-progress", "in-review", "c", "done", "no-go"))
                        && order(eff(scrum, reversed(many))).equals(order(eff(scrum, many))),
                order(eff(scrum, reversed(many))));

        // ---- the Refined column under template evolution ------------------
        section("template evolution: the instance-added Refined column");
        check("R1", "worked instance: the delta is one element, the diff one line, the board one INSERT plus ordinal shifts",
                refined.size() == 1 && Diff.overrides(scrum, refined).size() == 1
                        && Diff.projection(eff(scrum, none), worked.effective()).equals(List.of(
                        "INSERT refined name=\"Refined\" ordinal=1", "UPDATE todo ordinal 1 -> 2",
                        "UPDATE in-progress ordinal 2 -> 3", "UPDATE in-review ordinal 3 -> 4", "UPDATE done ordinal 4 -> 5", "UPDATE no-go ordinal 5 -> 6")),
                Diff.projection(eff(scrum, none), worked.effective()));
        Map<String, Object> addBlocked = next(scrum,
                "{\"states\": {\"blocked\": {\"name\": \"Blocked\", \"category\": \"IN_PROGRESS\"}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"blocked\", \"in-review\", \"done\", \"no-go\"]}");
        Resolver.Upgrade r2 = Resolver.upgrade(scrum, addBlocked, refined, empty);
        check("R2", "template adds a column elsewhere: inherited in place, Refined still after Backlog, delta untouched",
                r2.conflicts().isEmpty() && r2.delta().equals(refined) && order(r2.effective()).equals(
                        List.of("backlog", "refined", "todo", "in-progress", "blocked", "in-review", "done", "no-go")),
                order(r2.effective()));
        Map<String, Object> reordered = next(scrum,
                "{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        Resolver.Upgrade r3 = Resolver.upgrade(scrum, reordered, refined, empty);
        check("R3", "template reorders columns: the new order is inherited and Refined moves with Backlog",
                r3.conflicts().isEmpty() && r3.delta().equals(refined) && order(r3.effective()).equals(
                        List.of("todo", "backlog", "refined", "in-progress", "in-review", "done", "no-go")),
                order(r3.effective()));
        Map<String, Object> addTriage = next(scrum,
                "{\"states\": {\"triage\": {\"name\": \"Triage\", \"category\": \"START_STATE\"}},"
                        + " \"stateOrder\": [\"backlog\", \"triage\", \"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        Resolver.Upgrade r4 = Resolver.upgrade(scrum, addTriage, refined, empty);
        check("R4", "template inserts a column right after Backlog: Refined stays next to its anchor, the new one follows",
                r4.conflicts().isEmpty() && order(r4.effective()).equals(
                        List.of("backlog", "refined", "triage", "todo", "in-progress", "in-review", "done", "no-go")),
                order(r4.effective()));
        Map<String, Object> addReady = next(scrum,
                "{\"states\": {\"refined\": {\"name\": \"Ready\", \"category\": \"START_STATE\"}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"refined\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        Resolver.Upgrade r5 = Resolver.upgrade(scrum, addReady, refined, empty);
        check("R5", "template adds the same key itself (named Ready, placed elsewhere): adopted, instance name and place win",
                r5.conflicts().isEmpty() && is(name(r5.effective(), "refined"), "Refined")
                        && order(r5.effective()).equals(order(worked.effective()))
                        && has(r5.notes(), "states.refined: template now defines it too"),
                r5);
        Map<String, Object> noBacklog = next(scrum,
                "{\"states\": {\"backlog\": null}, \"stateOrder\": [\"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        Resolver.Upgrade r6a = Resolver.upgrade(scrum, noBacklog, refined, Map.of("states.backlog", 30));
        Resolver.Upgrade r6b = Resolver.upgrade(scrum, noBacklog, refined, empty);
        check("R6", "template removes the anchor (Backlog): refused while it holds items; empty, Refined stays first, anchored before To Do",
                has(r6a.conflicts(), "states.backlog: still used by 30")
                        && r6b.conflicts().isEmpty()
                        && order(r6b.effective()).equals(List.of("refined", "todo", "in-progress", "in-review", "done", "no-go"))
                        && !state(r6b.effective(), "refined").containsKey("after")
                        && is(state(r6b.effective(), "refined").get("before"), "todo")
                        && !r6b.delta().containsKey("stateOrder"),
                r6a.conflicts() + " " + r6b);
        Map<String, Object> dropReview = next(scrum,
                "{\"states\": {\"in-review\": null}, \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"done\", \"no-go\"]}");
        Map<String, Object> qaAfterReview = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\","
                + " \"after\": \"in-review\"}}}");
        Resolver.Upgrade r7 = Resolver.upgrade(scrum, dropReview, qaAfterReview, empty);
        check("R7", "template removes a mid-board anchor: the added state is re-anchored to its surviving predecessor",
                r7.conflicts().isEmpty() && is(state(r7.effective(), "qa").get("after"), "in-progress")
                        && order(r7.effective()).equals(List.of("backlog", "todo", "in-progress", "qa", "done", "no-go")),
                r7);
        Map<String, String> vault = Map.of("TO DO", "backlog", "REFINED", "refined", "PLANNED", "todo",
                "IN PROGRESS", "in-progress", "IN REVIEW", "in-review", "DONE", "done", "NO GO", "no-go");
        String endStates = "{\"states\": {\"wont-fix\": {\"name\": \"Won't Fix\", \"category\": \"END_STATE\", \"outcome\": \"DISCONTINUED\", \"after\": \"done\"},"
                + " \"duplicate\": {\"name\": \"Duplicate\", \"category\": \"END_STATE\", \"outcome\": \"DISCONTINUED\", \"after\": \"wont-fix\"}}}";
        Map<String, Object> ends = eff(scrum, patch(endStates));
        check("R8", "every backlog status maps to a state key of the Refined instance, NO GO to an end state with outcome DISCONTINUED; an unknown status falls back by category and outcome",
                states(worked.effective()).keySet().containsAll(vault.values())
                        && is(state(worked.effective(), vault.get("NO GO")).get("category"), "END_STATE")
                        && is(state(worked.effective(), vault.get("NO GO")).get("outcome"), "DISCONTINUED")
                        && is(state(worked.effective(), vault.get("DONE")).get("outcome"), "DELIVERED")
                        && !states(eff(scrum, none)).containsKey("refined")
                        && is(Resolver.fallback(worked.effective(), "START_STATE", null), "backlog")
                        && is(Resolver.fallback(worked.effective(), "IN_PROGRESS", null), "in-progress")
                        && is(Resolver.fallback(worked.effective(), "END_STATE", "DISCONTINUED"), "no-go")
                        && is(Resolver.fallback(ends, "END_STATE", "DISCONTINUED"), "wont-fix") && is(Resolver.fallback(ends, "END_STATE", "DELIVERED"), "done"),
                vault);
        String tail = "\"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"";
        Resolver.Upgrade r9a = inPlace(scrum, noBacklog, "\"backlog\": {\"name\": \"Icebox\"}, \"refined\": {\"name\": \"R\", " + x + ", \"after\": \"backlog\"}", "[\"backlog\", \"refined\", " + tail + "]");
        Resolver.Upgrade r9b = inPlace(scrum, noBacklog, "\"inbox\": {\"name\": \"I\", " + x + ", \"before\": \"backlog\"}, \"refined\": {\"name\": \"R\", " + x + ", \"after\": \"backlog\"}, \"b\": {\"name\": \"B\", " + x + ", \"after\": \"refined\"}", "[\"inbox\", \"refined\", \"b\", " + tail + "]");
        Resolver.Upgrade r9c = inPlace(scrum, next(scrum, "{\"states\": {\"backlog\": null, \"todo\": null}, \"stateOrder\": [\"in-progress\", \"in-review\", \"done\", \"no-go\"]}"), "\"backlog\": {\"name\": \"Icebox\"}, \"todo\": {\"name\": \"Next\"}", "[\"backlog\", " + tail + "]");
        Resolver.Upgrade r9d = inPlace(scrum, dropReview, "\"in-review\": {\"name\": \"CR\"}, \"pre\": {\"name\": \"P\", " + x + ", \"before\": \"in-review\"}", "[\"backlog\", \"todo\", \"in-progress\", \"pre\", \"in-review\", \"done\", \"no-go\"]");
        check("R9", "re-anchoring never anchors to a state placed relative to the moved one: no false cycle, same delta with members reversed",
                r9a != null && r9b != null && r9c != null && r9d != null && is(name(r9a.effective(), "backlog"), "Icebox")
                        && is(state(r9a.effective(), "backlog").get("before"), "todo") && is(state(r9a.effective(), "refined").get("after"), "backlog")
                        && is(state(r9d.effective(), "in-review").get("after"), "in-progress") && is(state(r9d.effective(), "pre").get("before"), "in-review"), r9a + " " + r9d);

        // ---- end states and their outcomes (done, no-go) ---------------
        section("end states and outcomes");
        check("D1", "both templates end in done (DELIVERED) and no-go (DISCONTINUED, off the board, still projected to a board_column row)",
                is(state(eff(kanban, none), "no-go").get("category"), "END_STATE") && is(state(eff(kanban, none), "no-go").get("outcome"), "DISCONTINUED")
                        && is(state(eff(scrum, none), "done").get("category"), "END_STATE") && is(state(eff(scrum, none), "done").get("outcome"), "DELIVERED")
                        && order(eff(kanban, none)).getLast().equals("no-go")
                        && order(eff(scrum, none)).getLast().equals("no-go")
                        && !Diff.onBoard(states(eff(scrum, none)), "no-go")
                        && Diff.projection(none, eff(scrum, none))
                        .contains("INSERT no-go name=\"No Go\" ordinal=5 on_board=false"),
                Diff.projection(none, eff(scrum, none)));
        String off = "{\"states\": {\"todo\": {\"onBoard\": false}, \"in-progress\": {\"onBoard\": false}";
        check("D2", "a discontinued end state is no delivery: dropping the only DELIVERED one is rejected; onBoard must be a boolean, and some state on the board",
                has(Resolver.resolve(scrum, patch("{\"states\": {\"done\": null}}")).violations(), "one START_STATE state and one END_STATE state with outcome DELIVERED")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"no-go\": {\"onBoard\": \"no\"}}}")).violations(), "no-go.onBoard: must be true or false")
                        && has(Resolver.resolve(kanban, patch(off + ", \"done\": {\"onBoard\": false}}}")).violations(), "states: needs at least one state on the board")
                        && Resolver.resolve(kanban, patch(off + "}}")).ok(), "");
        Map<String, Object> wontDo = patch("{\"states\": {\"no-go\": {\"name\": \"Won't Do\"}}}");
        Resolver.Upgrade d3 = Resolver.upgrade(scrum, next(scrum, "{\"states\": {\"no-go\": {\"name\": \"Abandoned\"}}}"), wontDo, empty);
        check("D3", "instance renames it: UPDATE of the same row, still off the board; a later template rename does not override it",
                Diff.projection(eff(scrum, none), eff(scrum, wontDo)).equals(List.of("UPDATE no-go name \"No Go\" -> \"Won't Do\""))
                        && !Diff.onBoard(states(eff(scrum, wontDo)), "no-go")
                        && d3.conflicts().isEmpty() && is(name(d3.effective(), "no-go"), "Won't Do"),
                Diff.projection(eff(scrum, none), eff(scrum, wontDo)));
        Map<String, Object> showNoGo = patch("{\"states\": {\"no-go\": {\"onBoard\": true}}}");
        check("D4", "instance puts it on the board: one flag UPDATE, no item moves",
                Diff.projection(eff(scrum, none), eff(scrum, showNoGo)).equals(List.of("UPDATE no-go on_board false -> true")),
                Diff.projection(eff(scrum, none), eff(scrum, showNoGo)));
        Map<String, Object> scrumWithout = Json.obj(Json.mergePatch(scrum, patch(
                "{\"states\": {\"no-go\": null}, \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"done\"]}")));
        Map<String, Object> gainsNoGo = next(scrumWithout,
                "{\"states\": {\"no-go\": {\"name\": \"No Go\", \"category\": \"END_STATE\", \"outcome\": \"DISCONTINUED\", \"onBoard\": false}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        Resolver.Upgrade d5 = Resolver.upgrade(scrumWithout, gainsNoGo, refined, empty);
        check("D5", "template gains it: inherited off the board, after Done, Refined untouched, delta not rewritten",
                d5.conflicts().isEmpty() && d5.delta().equals(refined)
                        && order(d5.effective()).equals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"))
                        && Diff.projection(eff(scrumWithout, refined), d5.effective())
                        .equals(List.of("INSERT no-go name=\"No Go\" ordinal=6 on_board=false")),
                d5);
        Resolver.Upgrade d6a = Resolver.upgrade(scrum, scrumWithout, none, Map.of("states.no-go", 2));
        Resolver.Upgrade d6b = Resolver.upgrade(scrum, scrumWithout, none, empty);
        Resolver.Upgrade d6c = Resolver.upgrade(scrum, scrumWithout, wontDo, Map.of("states.no-go", 2));
        check("D6", "template removes it: refused while it holds items; empty, removed; renamed by the instance, kept off the board",
                has(d6a.conflicts(), "states.no-go: still used by 2")
                        && d6b.conflicts().isEmpty() && Diff.projection(eff(scrum, none), d6b.effective()).equals(List.of("DELETE no-go"))
                        && d6c.conflicts().isEmpty() && is(state(d6c.effective(), "no-go").get("outcome"), "DISCONTINUED")
                        && !Diff.onBoard(states(d6c.effective()), "no-go") && order(d6c.effective()).getLast().equals("no-go"),
                d6a.conflicts() + " " + d6c);
        Map<String, Object> ownNoGo = patch("{\"states\": {\"no-go\": {\"name\": \"Abandoned\", \"category\": \"END_STATE\","
                + " \"outcome\": \"DISCONTINUED\", \"after\": \"done\"}}}");
        Resolver.Upgrade d7 = Resolver.upgrade(scrumWithout, gainsNoGo, ownNoGo, empty);
        check("D7", "instance had added its own no-go, then the template adds one: adopted, instance name wins; the template's onBoard=false is filled in and previewed",
                d7.conflicts().isEmpty() && is(name(d7.effective(), "no-go"), "Abandoned")
                        && has(d7.notes(), "states.no-go: template now defines it too; instance values win over [name], template fills [onBoard]")
                        && Diff.projection(eff(scrumWithout, ownNoGo), d7.effective()).equals(List.of("UPDATE no-go on_board true -> false")),
                d7);
        Resolver.Upgrade d8 = Resolver.upgrade(scrumWithout, gainsNoGo, patch("{\"states\": {\"no-go\": {\"name\": \"Abandoned\", \"category\": \"END_STATE\", \"outcome\": \"DELIVERED\", \"after\": \"done\"}}}"), empty);
        Resolver.Upgrade d8b = Resolver.upgrade(scrumWithout, gainsNoGo, patch("{\"states\": {\"no-go\": {\"name\": \"Parked\", \"category\": \"START_STATE\", \"after\": \"done\"}}}"), empty);
        check("D8", "instance had added its own no-go as a DELIVERED end state, or not as an end state, then the template adds it as DISCONTINUED: upgrade refused",
                has(d8.conflicts(), "states.no-go: the instance added it as END_STATE (DELIVERED), the template now defines it as END_STATE (DISCONTINUED)")
                        && has(d8.notes(), "instance values win over [name, outcome], template fills [onBoard]")
                        && has(d8b.conflicts(), "states.no-go: the instance added it as START_STATE, the template now defines it as END_STATE (DISCONTINUED)")
                        && has(d8b.notes(), "instance values win over [category, name], template fills [onBoard, outcome]"), d8 + " " + d8b);
        String end = "\"category\": \"END_STATE\"";
        check("D9", "an END_STATE declares its outcome, DELIVERED or DISCONTINUED, and no other state has one; a team adds its own end states",
                has(Resolver.resolve(scrum, patch("{\"states\": {\"wont-fix\": {\"name\": \"W\", " + end + ", \"after\": \"done\"}}}")).violations(),
                        "states.wont-fix.outcome: an END_STATE state must declare one of [DELIVERED, DISCONTINUED], was null")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"done\": {\"outcome\": \"SHIPPED\"}}}")).violations(), "states.done.outcome: an END_STATE state must declare")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"todo\": {\"outcome\": \"DELIVERED\"}}}")).violations(), "states.todo.outcome: only an END_STATE state has an outcome")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"done\": {\"category\": \"IN_PROGRESS\"}}}")).violations(), "states.done.outcome: only an END_STATE state has an outcome")
                        && has(Resolver.resolve(scrum, patch("{\"states\": {\"done\": {\"outcome\": \"DISCONTINUED\"}}}")).violations(), "one END_STATE state with outcome DELIVERED")
                        && Resolver.resolve(scrum, patch(endStates)).ok() && order(ends).equals(List.of("backlog", "todo", "in-progress", "in-review", "done", "wont-fix", "duplicate", "no-go"))
                        && Resolver.resolve(scrum, patch("{\"states\": {\"done\": {\"category\": \"IN_PROGRESS\", \"outcome\": null}, \"released\": {\"name\": \"Released\", "
                        + end + ", \"outcome\": \"DELIVERED\", \"after\": \"done\"}}}")).ok(), "");

        // ---- template evolution: states (columns) --------------------
        section("template evolution: states");
        Resolver.Upgrade c1 = Resolver.upgrade(scrum, addBlocked, none, empty);
        check("C1", "template adds a state; instance untouched: inherited at the template's position",
                c1.conflicts().isEmpty()
                        && order(c1.effective()).equals(List.of("backlog", "todo", "in-progress", "blocked", "in-review", "done", "no-go")),
                order(c1.effective()));
        Resolver.Upgrade c2 = Resolver.upgrade(scrum, addBlocked, reorder, empty);
        check("C2", "template adds a state; instance reordered: inherited, placed after its template predecessor",
                c2.conflicts().isEmpty() && c2.delta().equals(reorder)
                        && order(c2.effective()).equals(List.of("todo", "backlog", "in-progress", "blocked", "in-review", "done", "no-go")),
                order(c2.effective()));
        Map<String, Object> addQa = next(scrum,
                "{\"states\": {\"qa\": {\"name\": \"Testing\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"in-review\"]}},"
                        + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"qa\", \"done\", \"no-go\"]}");
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
                        && Diff.projection(eff(scrum, none), c7.effective()).getFirst().equals("DELETE in-review"),
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
                order(Resolver.upgrade(scrum, reordered, none, empty).effective())
                        .equals(List.of("todo", "backlog", "in-progress", "in-review", "done", "no-go")), "");
        Map<String, Object> ownOrder = patch("{\"stateOrder\": [\"backlog\", \"todo\", \"in-review\", \"in-progress\", \"done\", \"no-go\"]}");
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
                t5.conflicts().isEmpty() && is(Json.obj(types(t5.effective()).get("task")).get("name"), "Chore")
                        && has(t5.notes(), "itemTypes.task: template removed it; the instance customised it, so it keeps it as its own")
                        && is(Json.obj(t5.delta().get("itemTypes")).get("task"), Json.parse("{\"name\": \"Chore\"}")), t5);
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
}
