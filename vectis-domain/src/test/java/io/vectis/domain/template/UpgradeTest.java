package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.eff;
import static io.vectis.domain.template.Fixtures.has;
import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.name;
import static io.vectis.domain.template.Fixtures.next;
import static io.vectis.domain.template.Fixtures.order;
import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Fixtures.reversed;
import static io.vectis.domain.template.Fixtures.state;
import static io.vectis.domain.template.Fixtures.states;
import static io.vectis.domain.template.Fixtures.types;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The template-evolution cases of the VEC-45 spike ({@code docs/spikes/template-instance-model/}),
 * against the production code. Case ids are the spike's.
 */
class UpgradeTest {

    static final Map<String, Object> SCRUM = load("vec45/scrum.v1.json");
    static final Map<String, Object> REFINED = load("vec45/instance-refined.json");
    static final Map<String, Object> REVIEW = load("vec45/instance-review.json");
    static final Map<String, Object> NONE = Map.of();
    static final Map<String, Integer> EMPTY = Map.of();
    static final String START = "\"category\": \"START_STATE\"";
    static final String TAIL = "\"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"";

    static final Map<String, Object> ADD_BLOCKED = next(SCRUM, "{\"states\": {\"blocked\": {\"name\": \"Blocked\", \"category\": \"IN_PROGRESS\"}},"
            + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"blocked\", \"in-review\", \"done\", \"no-go\"]}");
    static final Map<String, Object> REORDERED = next(SCRUM, "{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
    static final Map<String, Object> NO_BACKLOG = next(SCRUM, "{\"states\": {\"backlog\": null}, \"stateOrder\": [" + TAIL + "]}");
    static final Map<String, Object> DROP_REVIEW = next(SCRUM, "{\"states\": {\"in-review\": null},"
            + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"done\", \"no-go\"]}");
    static final Map<String, Object> RENAMES = next(SCRUM, "{\"states\": {\"in-progress\": {\"name\": \"Doing\"},"
            + " \"in-review\": {\"name\": \"Peer Review\"}, \"backlog\": {\"name\": \"Icebox\"}}}");
    static final Map<String, Object> REORDER = patch("{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
    static final Map<String, Object> DROP_BACKLOG = patch("{\"states\": {\"backlog\": null}}");
    static final Map<String, Object> TOMB_REVIEW = patch("{\"states\": {\"in-review\": null}}");
    static final Map<String, Object> WONT_DO = patch("{\"states\": {\"no-go\": {\"name\": \"Won't Do\"}}}");
    static final Map<String, Object> SCRUM_WITHOUT_NO_GO = TemplateJson.obj(TemplateJson.mergePatch(SCRUM, patch(
            "{\"states\": {\"no-go\": null}, \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"done\"]}")));
    static final Map<String, Object> GAINS_NO_GO = next(SCRUM_WITHOUT_NO_GO, "{\"states\": {\"no-go\": {\"name\": \"No Go\","
            + " \"category\": \"END_STATE\", \"outcome\": \"DISCONTINUED\", \"onBoard\": false}},"
            + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");

    static Upgrade upgrade(Map<String, Object> from, Map<String, Object> to, Map<String, Object> delta, Map<String, Integer> occupancy) {
        return Upgrade.of(from, to, delta, occupancy);
    }

    static void clean(Upgrade u) {
        assertEquals(List.of(), u.conflicts(), u::toString);
    }

    /** Upgrades {@code states} and its member-reversed copy and checks both keep the board as {@code order}, with no conflict and no pinned stateOrder. */
    static Upgrade inPlace(Map<String, Object> from, Map<String, Object> to, String states, List<String> order) {
        Upgrade u = upgrade(from, to, patch("{\"states\": {" + states + "}}"), EMPTY);
        Upgrade w = upgrade(from, to, reversed(patch("{\"states\": {" + states + "}}")), EMPTY);
        clean(u);
        clean(w);
        assertEquals(u.delta(), w.delta());
        assertFalse(u.delta().containsKey("stateOrder"), u::toString);
        assertEquals(order, order(u.effective()));
        assertEquals(order, order(w.effective()));
        return u;
    }

    // ---- the instance-added Refined column -----------------------------

    @Test
    @DisplayName("R2 template adds a column elsewhere: inherited in place, Refined still after Backlog, delta untouched")
    void r2() {
        Upgrade u = upgrade(SCRUM, ADD_BLOCKED, REFINED, EMPTY);
        clean(u);
        assertEquals(REFINED, u.delta());
        assertEquals(List.of("backlog", "refined", "todo", "in-progress", "blocked", "in-review", "done", "no-go"), order(u.effective()));
    }

    @Test
    @DisplayName("R3 template reorders columns: the new order is inherited and Refined moves with Backlog")
    void r3() {
        Upgrade u = upgrade(SCRUM, REORDERED, REFINED, EMPTY);
        clean(u);
        assertEquals(REFINED, u.delta());
        assertEquals(List.of("todo", "backlog", "refined", "in-progress", "in-review", "done", "no-go"), order(u.effective()));
    }

    @Test
    @DisplayName("R4 template inserts a column right after Backlog: Refined stays next to its anchor, the new one follows")
    void r4() {
        Map<String, Object> addTriage = next(SCRUM, "{\"states\": {\"triage\": {\"name\": \"Triage\", " + START + "}},"
                + " \"stateOrder\": [\"backlog\", \"triage\", " + TAIL + "]}");
        Upgrade u = upgrade(SCRUM, addTriage, REFINED, EMPTY);
        clean(u);
        assertEquals(List.of("backlog", "refined", "triage", "todo", "in-progress", "in-review", "done", "no-go"), order(u.effective()));
    }

    @Test
    @DisplayName("R5 template adds the same key itself (named Ready, placed elsewhere): adopted, instance name and place win")
    void r5() {
        Map<String, Object> addReady = next(SCRUM, "{\"states\": {\"refined\": {\"name\": \"Ready\", " + START + "}},"
                + " \"stateOrder\": [\"backlog\", \"todo\", \"refined\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        Upgrade u = upgrade(SCRUM, addReady, REFINED, EMPTY);
        clean(u);
        assertEquals("Refined", name(u.effective(), "refined"));
        assertEquals(order(eff(SCRUM, REFINED)), order(u.effective()));
        assertTrue(has(u.notes(), "states.refined: template now defines it too"), u::toString);
    }

    @Test
    @DisplayName("R6 template removes the anchor (Backlog): refused while it holds items; empty, Refined stays first, anchored before To Do")
    void r6() {
        assertTrue(has(upgrade(SCRUM, NO_BACKLOG, REFINED, Map.of("states.backlog", 30)).conflicts(), "states.backlog: still used by 30"));
        Upgrade u = upgrade(SCRUM, NO_BACKLOG, REFINED, EMPTY);
        clean(u);
        assertEquals(List.of("refined", "todo", "in-progress", "in-review", "done", "no-go"), order(u.effective()));
        assertFalse(state(u.effective(), "refined").containsKey("after"));
        assertEquals("todo", state(u.effective(), "refined").get("before"));
        assertFalse(u.delta().containsKey("stateOrder"));
    }

    @Test
    @DisplayName("R7 template removes a mid-board anchor: the added state is re-anchored to its surviving predecessor")
    void r7() {
        Upgrade u = upgrade(SCRUM, DROP_REVIEW, patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\","
                + " \"after\": \"in-review\"}}}"), EMPTY);
        clean(u);
        assertEquals("in-progress", state(u.effective(), "qa").get("after"));
        assertEquals(List.of("backlog", "todo", "in-progress", "qa", "done", "no-go"), order(u.effective()));
    }

    @Test
    @DisplayName("R8 an unknown status falls back to the first state with its category and, for an end state, its outcome")
    void r8() {
        Map<String, Object> worked = eff(SCRUM, REFINED);
        assertEquals("backlog", Upgrade.fallback(worked, "START_STATE", null));
        assertEquals("in-progress", Upgrade.fallback(worked, "IN_PROGRESS", null));
        assertEquals("no-go", Upgrade.fallback(worked, "END_STATE", "DISCONTINUED"));
        Map<String, Object> ends = eff(SCRUM, patch("{\"states\": {\"wont-fix\": {\"name\": \"Won't Fix\", \"category\": \"END_STATE\","
                + " \"outcome\": \"DISCONTINUED\", \"after\": \"done\"}, \"duplicate\": {\"name\": \"Duplicate\", \"category\": \"END_STATE\","
                + " \"outcome\": \"DISCONTINUED\", \"after\": \"wont-fix\"}}}"));
        assertEquals("wont-fix", Upgrade.fallback(ends, "END_STATE", "DISCONTINUED"));
        assertEquals("done", Upgrade.fallback(ends, "END_STATE", "DELIVERED"));
        assertNull(Upgrade.fallback(eff(SCRUM, NONE), "END_STATE", null));
        assertNull(Upgrade.fallback(eff(SCRUM, NONE), "ARCHIVED", null));
    }

    @Test
    @DisplayName("R9 re-anchoring never anchors to a state placed relative to the moved one: no false cycle, same delta with members reversed")
    void r9() {
        Upgrade a = inPlace(SCRUM, NO_BACKLOG, "\"backlog\": {\"name\": \"Icebox\"}, \"refined\": {\"name\": \"R\", " + START + ", \"after\": \"backlog\"}",
                List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"));
        inPlace(SCRUM, NO_BACKLOG, "\"inbox\": {\"name\": \"I\", " + START + ", \"before\": \"backlog\"}, \"refined\": {\"name\": \"R\", " + START
                        + ", \"after\": \"backlog\"}, \"b\": {\"name\": \"B\", " + START + ", \"after\": \"refined\"}",
                List.of("inbox", "refined", "b", "todo", "in-progress", "in-review", "done", "no-go"));
        inPlace(SCRUM, next(SCRUM, "{\"states\": {\"backlog\": null, \"todo\": null}, \"stateOrder\": [\"in-progress\", \"in-review\", \"done\", \"no-go\"]}"),
                "\"backlog\": {\"name\": \"Icebox\"}, \"todo\": {\"name\": \"Next\"}", List.of("backlog", "todo", "in-progress", "in-review", "done", "no-go"));
        Upgrade d = inPlace(SCRUM, DROP_REVIEW, "\"in-review\": {\"name\": \"CR\"}, \"pre\": {\"name\": \"P\", " + START + ", \"before\": \"in-review\"}",
                List.of("backlog", "todo", "in-progress", "pre", "in-review", "done", "no-go"));
        assertEquals("Icebox", name(a.effective(), "backlog"));
        assertEquals("todo", state(a.effective(), "backlog").get("before"));
        assertEquals("backlog", state(a.effective(), "refined").get("after"));
        assertEquals("in-progress", state(d.effective(), "in-review").get("after"));
        assertEquals("in-review", state(d.effective(), "pre").get("before"));
    }

    // ---- end states and their outcomes ---------------------------------

    @Test
    @DisplayName("D3 instance renames no-go: a later template rename does not override it")
    void d3() {
        Upgrade u = upgrade(SCRUM, next(SCRUM, "{\"states\": {\"no-go\": {\"name\": \"Abandoned\"}}}"), WONT_DO, EMPTY);
        clean(u);
        assertEquals("Won't Do", name(u.effective(), "no-go"));
    }

    @Test
    @DisplayName("D5 template gains no-go: inherited off the board, after Done, Refined untouched, delta not rewritten")
    void d5() {
        Upgrade u = upgrade(SCRUM_WITHOUT_NO_GO, GAINS_NO_GO, REFINED, EMPTY);
        clean(u);
        assertEquals(REFINED, u.delta());
        assertEquals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"), order(u.effective()));
        assertEquals(List.of("INSERT no-go name=\"No Go\" ordinal=6 on_board=false"),
                Diff.projection(eff(SCRUM_WITHOUT_NO_GO, REFINED), u.effective()));
    }

    @Test
    @DisplayName("D6 template removes no-go: refused while it holds items; empty, removed; renamed by the instance, kept off the board")
    void d6() {
        assertTrue(has(upgrade(SCRUM, SCRUM_WITHOUT_NO_GO, NONE, Map.of("states.no-go", 2)).conflicts(), "states.no-go: still used by 2"));
        Upgrade empty = upgrade(SCRUM, SCRUM_WITHOUT_NO_GO, NONE, EMPTY);
        clean(empty);
        assertEquals(List.of("DELETE no-go"), Diff.projection(eff(SCRUM, NONE), empty.effective()));
        Upgrade kept = upgrade(SCRUM, SCRUM_WITHOUT_NO_GO, WONT_DO, Map.of("states.no-go", 2));
        clean(kept);
        assertEquals("DISCONTINUED", state(kept.effective(), "no-go").get("outcome"));
        assertFalse(Diff.onBoard(states(kept.effective()), "no-go"));
        assertEquals("no-go", order(kept.effective()).getLast());
    }

    @Test
    @DisplayName("D7 instance had added its own no-go, then the template adds one: adopted, instance name wins; the template's onBoard=false is filled in and previewed")
    void d7() {
        Map<String, Object> own = patch("{\"states\": {\"no-go\": {\"name\": \"Abandoned\", \"category\": \"END_STATE\","
                + " \"outcome\": \"DISCONTINUED\", \"after\": \"done\"}}}");
        Upgrade u = upgrade(SCRUM_WITHOUT_NO_GO, GAINS_NO_GO, own, EMPTY);
        clean(u);
        assertEquals("Abandoned", name(u.effective(), "no-go"));
        assertTrue(u.notes().contains(new Upgrade.Note("states.no-go",
                "template now defines it too; instance values win over [name], template fills [onBoard]")), u::toString);
        assertEquals(List.of("UPDATE no-go on_board true -> false"), Diff.projection(eff(SCRUM_WITHOUT_NO_GO, own), u.effective()));
    }

    @Test
    @DisplayName("D8 instance had added its own no-go as a DELIVERED end state, or not as an end state, then the template adds it as DISCONTINUED: upgrade refused")
    void d8() {
        Upgrade delivered = upgrade(SCRUM_WITHOUT_NO_GO, GAINS_NO_GO, patch("{\"states\": {\"no-go\": {\"name\": \"Abandoned\","
                + " \"category\": \"END_STATE\", \"outcome\": \"DELIVERED\", \"after\": \"done\"}}}"), EMPTY);
        Upgrade parked = upgrade(SCRUM_WITHOUT_NO_GO, GAINS_NO_GO, patch("{\"states\": {\"no-go\": {\"name\": \"Parked\", "
                + START + ", \"after\": \"done\"}}}"), EMPTY);
        assertTrue(has(delivered.conflicts(), "states.no-go: the instance added it as END_STATE (DELIVERED), the template now defines it as END_STATE (DISCONTINUED)"),
                delivered::toString);
        assertTrue(has(delivered.notes(), "instance values win over [name, outcome], template fills [onBoard]"), delivered::toString);
        assertTrue(has(parked.conflicts(), "states.no-go: the instance added it as START_STATE, the template now defines it as END_STATE (DISCONTINUED)"),
                parked::toString);
        assertTrue(has(parked.notes(), "instance values win over [category, name], template fills [onBoard, outcome]"), parked::toString);
        assertFalse(delivered.ok());
    }

    // ---- states (columns) ----------------------------------------------

    @Test
    @DisplayName("C1 template adds a state; instance untouched: inherited at the template's position")
    void c1() {
        Upgrade u = upgrade(SCRUM, ADD_BLOCKED, NONE, EMPTY);
        clean(u);
        assertEquals(List.of("backlog", "todo", "in-progress", "blocked", "in-review", "done", "no-go"), order(u.effective()));
    }

    @Test
    @DisplayName("C2 template adds a state; instance reordered: inherited, placed after its template predecessor")
    void c2() {
        Upgrade u = upgrade(SCRUM, ADD_BLOCKED, REORDER, EMPTY);
        clean(u);
        assertEquals(REORDER, u.delta());
        assertEquals(List.of("todo", "backlog", "in-progress", "blocked", "in-review", "done", "no-go"), order(u.effective()));
    }

    @Test
    @DisplayName("C3 template adds a state the instance had already added under that key: adopted, instance values win")
    void c3() {
        Map<String, Object> addQa = next(SCRUM, "{\"states\": {\"qa\": {\"name\": \"Testing\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"in-review\"]}},"
                + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"in-review\", \"qa\", \"done\", \"no-go\"]}");
        Upgrade u = upgrade(SCRUM, addQa, REVIEW, EMPTY);
        clean(u);
        assertEquals("QA", name(u.effective(), "qa"));
        assertEquals(3L, state(u.effective(), "qa").get("wipLimit"));
        assertEquals(List.of("in-review"), state(u.effective(), "qa").get("enterFrom"));
        assertTrue(has(u.notes(), "states.qa: template now defines it too"), u::toString);
    }

    @Test
    @DisplayName("C4/C5/C6 template renames states: an untouched instance inherits, its own rename is kept, its deletion stays")
    void c4c5c6() {
        assertEquals("Doing", name(upgrade(SCRUM, RENAMES, NONE, EMPTY).effective(), "in-progress"));
        Upgrade renamed = upgrade(SCRUM, RENAMES, REVIEW, EMPTY);
        assertEquals("Code Review", name(renamed.effective(), "in-review"));
        assertEquals("Doing", name(renamed.effective(), "in-progress"));
        Upgrade deleted = upgrade(SCRUM, RENAMES, DROP_BACKLOG, EMPTY);
        clean(deleted);
        assertFalse(states(deleted.effective()).containsKey("backlog"));
        assertEquals(DROP_BACKLOG, deleted.delta());
    }

    @Test
    @DisplayName("C7/C8 template removes a state; instance untouched: removed when empty (DELETE), refused while items are in it")
    void c7c8() {
        Upgrade empty = upgrade(SCRUM, DROP_REVIEW, NONE, EMPTY);
        clean(empty);
        assertFalse(states(empty.effective()).containsKey("in-review"));
        assertEquals("DELETE in-review", Diff.projection(eff(SCRUM, NONE), empty.effective()).getFirst());
        assertEquals(List.of(new Violation("states.in-review", null, "still used by 4 item(s); move or retype them first")),
                upgrade(SCRUM, DROP_REVIEW, NONE, Map.of("states.in-review", 4)).conflicts());
    }

    @Test
    @DisplayName("C9 template removes a state; instance renamed it: promoted to the instance's own, kept in place")
    void c9() {
        Upgrade u = upgrade(SCRUM, DROP_REVIEW, REVIEW, Map.of("states.in-review", 4));
        clean(u);
        assertEquals("Code Review", name(u.effective(), "in-review"));
        assertEquals("IN_PROGRESS", state(u.effective(), "in-review").get("category"));
        assertEquals("in-progress", state(u.effective(), "in-review").get("after"));
        assertEquals(order(eff(SCRUM, REVIEW)), order(u.effective()));
        assertFalse(u.delta().containsKey("stateOrder"));
        assertTrue(has(u.notes(), "keeps it as its own"), u::toString);
    }

    @Test
    @DisplayName("C10 template removes a state; instance deleted it: redundant tombstone dropped, delta now empty")
    void c10() {
        Upgrade u = upgrade(SCRUM, DROP_REVIEW, TOMB_REVIEW, EMPTY);
        clean(u);
        assertEquals(Map.of(), u.delta());
    }

    @Test
    @DisplayName("C11/C12 template reorders states: an untouched instance inherits the order; one with its own keeps it, and the upgrade says so")
    void c11c12() {
        assertEquals(List.of("todo", "backlog", "in-progress", "in-review", "done", "no-go"), order(upgrade(SCRUM, REORDERED, NONE, EMPTY).effective()));
        Map<String, Object> own = patch("{\"stateOrder\": [\"backlog\", \"todo\", \"in-review\", \"in-progress\", \"done\", \"no-go\"]}");
        Upgrade u = upgrade(SCRUM, REORDERED, own, EMPTY);
        assertEquals(order(eff(SCRUM, own)), order(u.effective()));
        assertTrue(u.notes().contains(new Upgrade.Note("stateOrder", "template reordered its states; the instance keeps its own order")), u::toString);
    }

    @Test
    @DisplayName("C13 template removes a state an instance policy still references: upgrade refused")
    void c13() {
        Upgrade u = upgrade(SCRUM, DROP_REVIEW, patch("{\"states\": {\"done\": {\"enterFrom\": [\"in-review\"]}}}"), EMPTY);
        assertTrue(has(u.conflicts(), "states.done.enterFrom: references unknown state 'in-review'"), u::toString);
    }

    @Test
    @DisplayName("C14 template recategorises a state: refused while items are in it, noted when empty, kept when the instance sets its own")
    void c14() {
        Map<String, Object> noGoDelivers = next(SCRUM, "{\"states\": {\"no-go\": {\"outcome\": \"DELIVERED\"}}}");
        Map<String, Object> reviewEnds = next(SCRUM, "{\"states\": {\"in-review\": {\"category\": \"END_STATE\", \"outcome\": \"DELIVERED\"}}}");
        assertTrue(has(upgrade(SCRUM, noGoDelivers, NONE, Map.of("states.no-go", 9)).conflicts(),
                "states.no-go: template changes it from END_STATE (DISCONTINUED) to END_STATE (DELIVERED) while 9 item(s) are in it"));
        Upgrade empty = upgrade(SCRUM, noGoDelivers, NONE, EMPTY);
        clean(empty);
        assertTrue(has(empty.notes(), "states.no-go: template changes it from END_STATE (DISCONTINUED) to END_STATE (DELIVERED); it holds no items"));
        Upgrade own = upgrade(SCRUM, noGoDelivers, patch("{\"states\": {\"no-go\": {\"outcome\": \"DISCONTINUED\"}}}"), Map.of("states.no-go", 9));
        clean(own);
        assertTrue(has(own.notes(), "the instance's own category and outcome are kept"));
        assertEquals("DISCONTINUED", state(own.effective(), "no-go").get("outcome"));
        assertTrue(has(upgrade(SCRUM, reviewEnds, NONE, Map.of("states.in-review", 4)).conflicts(),
                "states.in-review: template changes it from IN_PROGRESS to END_STATE (DELIVERED) while 4 item(s)"));
        assertFalse(has(upgrade(SCRUM, reviewEnds, TOMB_REVIEW, EMPTY).notes(), "in-review"));
    }

    // ---- item types ----------------------------------------------------

    @Test
    @DisplayName("T1/T2 template adds an item type: inherited; one the instance had already added is adopted, instance name wins")
    void t1t2() {
        Map<String, Object> addSpike = next(SCRUM, "{\"itemTypes\": {\"spike\": {\"name\": \"Spike\"}}}");
        assertTrue(types(upgrade(SCRUM, addSpike, NONE, EMPTY).effective()).containsKey("spike"));
        Upgrade u = upgrade(SCRUM, addSpike, patch("{\"itemTypes\": {\"spike\": {\"name\": \"Research spike\"}}}"), EMPTY);
        assertEquals("Research spike", TemplateJson.obj(types(u.effective()).get("spike")).get("name"));
        assertTrue(has(u.notes(), "itemTypes.spike: template now defines it too"), u::toString);
    }

    @Test
    @DisplayName("T3/T4/T5 template removes an item type: removed when unused, refused while used, promoted when the instance renamed it")
    void t3t4t5() {
        Map<String, Object> dropTask = next(SCRUM, "{\"itemTypes\": {\"task\": null}}");
        Upgrade unused = upgrade(SCRUM, dropTask, NONE, EMPTY);
        clean(unused);
        assertFalse(types(unused.effective()).containsKey("task"));
        assertTrue(has(upgrade(SCRUM, dropTask, NONE, Map.of("itemTypes.task", 7)).conflicts(), "itemTypes.task: still used by 7"));
        Upgrade renamed = upgrade(SCRUM, dropTask, patch("{\"itemTypes\": {\"task\": {\"name\": \"Chore\"}}}"), Map.of("itemTypes.task", 7));
        clean(renamed);
        assertEquals("Chore", TemplateJson.obj(types(renamed.effective()).get("task")).get("name"));
        assertTrue(has(renamed.notes(), "itemTypes.task: template removed it; the instance customised it, so it keeps it as its own"));
        assertEquals(patch("{\"name\": \"Chore\"}"), TemplateJson.obj(renamed.delta().get("itemTypes")).get("task"));
    }

    @Test
    @DisplayName("T6/T7 template removes or renames an item type the instance deleted: the tombstone is dropped, or it stays deleted")
    void t6t7() {
        Upgrade removed = upgrade(SCRUM, next(SCRUM, "{\"itemTypes\": {\"bug\": null}}"), REVIEW, EMPTY);
        clean(removed);
        assertFalse(removed.delta().containsKey("itemTypes"));
        assertFalse(types(removed.effective()).containsKey("bug"));
        Upgrade renamed = upgrade(SCRUM, next(SCRUM, "{\"itemTypes\": {\"bug\": {\"name\": \"Defect\"}}}"), REVIEW, EMPTY);
        assertFalse(types(renamed.effective()).containsKey("bug"));
        assertEquals(REVIEW, renamed.delta());
    }

    // ---- properties ----------------------------------------------------

    @Test
    @DisplayName("an upgrade is deterministic and pure: the same inputs, members reversed, give the same result, and no input is modified")
    void deterministicAndPure() {
        Map<String, Object> delta = load("vec45/instance-review.json");
        Upgrade u = upgrade(SCRUM, DROP_REVIEW, delta, EMPTY);
        assertEquals(load("vec45/instance-review.json"), delta);
        Upgrade w = upgrade(SCRUM, DROP_REVIEW, reversed(delta), EMPTY);
        assertEquals(u.delta(), w.delta());
        assertEquals(u.effective(), w.effective());
        assertEquals(u.notes(), w.notes());
        assertEquals(u, upgrade(SCRUM, DROP_REVIEW, delta, EMPTY));
    }

    @Test
    @DisplayName("the upgraded delta, written over the new version, is accepted: an upgrade never produces a delta its own write would refuse")
    void upgradedDeltaIsWritable() {
        for (Map<String, Object> delta : List.of(REFINED, REVIEW, DROP_BACKLOG, TOMB_REVIEW, REORDER)) {
            Upgrade u = upgrade(SCRUM, DROP_REVIEW, delta, EMPTY);
            if (u.ok()) {
                assertTrue(DeltaWriter.write(DROP_REVIEW, u.delta(), u.delta(), EMPTY).ok(), u::toString);
                assertEquals(u.effective(), eff(DROP_REVIEW, u.delta()));
            }
        }
    }
}
