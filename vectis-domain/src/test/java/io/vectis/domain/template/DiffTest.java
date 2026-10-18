package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.eff;
import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Fixtures.states;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The override-list and board-projection cases of the VEC-45 spike, against the production code. */
class DiffTest {

    static final Map<String, Object> SCRUM = load("vec45/scrum.v1.json");
    static final Map<String, Object> KANBAN = load("vec45/kanban.v1.json");
    static final Map<String, Object> REFINED = load("vec45/instance-refined.json");
    static final Map<String, Object> REVIEW = load("vec45/instance-review.json");
    static final Map<String, Object> NONE = Map.of();

    @Test
    @DisplayName("I1 rename a state: UPDATE of the same board_column row, items do not move")
    void i1() {
        assertEquals(List.of("UPDATE in-review name \"In Review\" -> \"Code Review\""),
                Diff.projection(eff(SCRUM, NONE), eff(SCRUM, patch("{\"states\": {\"in-review\": {\"name\": \"Code Review\"}}}"))));
    }

    @Test
    @DisplayName("I3 remove an empty state: projects to DELETE")
    void i3() {
        assertTrue(Diff.projection(eff(SCRUM, NONE), eff(SCRUM, patch("{\"states\": {\"backlog\": null}}"))).contains("DELETE backlog"));
    }

    @Test
    @DisplayName("I5 reorder states: order replaced whole, projects to ordinal UPDATEs only")
    void i5() {
        Map<String, Object> reorder = patch("{\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}");
        assertEquals(List.of("UPDATE todo ordinal 1 -> 0", "UPDATE backlog ordinal 0 -> 1"),
                Diff.projection(eff(SCRUM, NONE), eff(SCRUM, reorder)));
    }

    @Test
    @DisplayName("R1 worked instance: the delta is one element, the diff one line, the board one INSERT plus ordinal shifts")
    void r1() {
        assertEquals(1, REFINED.size());
        assertEquals(List.of(new Diff.Change(Diff.Op.ADD, "states.refined", null, Fixtures.state(REFINED, "refined"))),
                Diff.overrides(SCRUM, REFINED));
        assertEquals(List.of("INSERT refined name=\"Refined\" ordinal=1", "UPDATE todo ordinal 1 -> 2",
                        "UPDATE in-progress ordinal 2 -> 3", "UPDATE in-review ordinal 3 -> 4", "UPDATE done ordinal 4 -> 5",
                        "UPDATE no-go ordinal 5 -> 6"),
                Diff.projection(eff(SCRUM, NONE), eff(SCRUM, REFINED)));
    }

    @Test
    @DisplayName("the review instance's override list: a change, an add, a field-level add and a remove, one line each, in key-path order")
    void reviewOverrides() {
        assertEquals(List.of(
                        "remove  itemTypes.bug   (template: {\"name\": \"Bug\"})",
                        "add     states.done.enterFrom = [\"qa\"]",
                        "change  states.in-review.name: \"In Review\" -> \"Code Review\"",
                        "add     states.qa = {\"name\": \"QA\", \"category\": \"IN_PROGRESS\", \"wipLimit\": 3, \"after\": \"in-review\"}"),
                Diff.overrides(SCRUM, REVIEW).stream().map(Object::toString).toList());
    }

    @Test
    @DisplayName("settings, cadence and stateOrder overrides are listed; an unset names the template value")
    void sectionOverrides() {
        Map<String, Object> delta = patch("{\"settings\": {\"defaultItemType\": \"task\"}, \"cadence\": {\"sprintDays\": null},"
                + " \"stateOrder\": [\"todo\", \"backlog\"]}");
        Map<String, Object> parent = patch("{\"settings\": {\"defaultItemType\": \"story\"}, \"cadence\": {\"mode\": \"sprint\","
                + " \"sprintDays\": 14}, \"stateOrder\": [\"backlog\", \"todo\"]}");
        assertEquals(List.of(
                        new Diff.Change(Diff.Op.UNSET, "cadence.sprintDays", 14L, null),
                        new Diff.Change(Diff.Op.CHANGE, "settings.defaultItemType", "story", "task"),
                        new Diff.Change(Diff.Op.CHANGE, "stateOrder", List.of("backlog", "todo"), List.of("todo", "backlog"))),
                Diff.overrides(parent, delta));
        assertEquals("unset   cadence.sprintDays   (template: 14)", Diff.overrides(parent, delta).getFirst().toString());
    }

    @Test
    @DisplayName("D1 both templates end in no-go, off the board and still projected to a board_column row")
    void d1() {
        assertFalse(Diff.onBoard(states(eff(SCRUM, NONE)), "no-go"));
        assertFalse(Diff.onBoard(states(eff(KANBAN, NONE)), "no-go"));
        assertTrue(Diff.onBoard(states(eff(SCRUM, NONE)), "done"));
        assertTrue(Diff.projection(NONE, eff(SCRUM, NONE)).contains("INSERT no-go name=\"No Go\" ordinal=5 on_board=false"));
    }

    @Test
    @DisplayName("D3/D4 the instance renames no-go or puts it on the board: one UPDATE of the same row each")
    void d3d4() {
        Map<String, Object> wontDo = patch("{\"states\": {\"no-go\": {\"name\": \"Won't Do\"}}}");
        assertEquals(List.of("UPDATE no-go name \"No Go\" -> \"Won't Do\""), Diff.projection(eff(SCRUM, NONE), eff(SCRUM, wontDo)));
        assertFalse(Diff.onBoard(states(eff(SCRUM, wontDo)), "no-go"));
        assertEquals(List.of("UPDATE no-go on_board false -> true"),
                Diff.projection(eff(SCRUM, NONE), eff(SCRUM, patch("{\"states\": {\"no-go\": {\"onBoard\": true}}}"))));
    }

    @Test
    @DisplayName("an unchanged configuration projects to nothing")
    void noChange() {
        assertEquals(List.of(), Diff.projection(eff(SCRUM, REVIEW), eff(SCRUM, REVIEW)));
        assertEquals(List.of(), Diff.overrides(SCRUM, NONE));
    }

    @Test
    @DisplayName("the override list ignores member order (jsonb keeps none): a delta and its member-reversed copy list the same lines")
    void overridesIgnoreMemberOrder() {
        assertEquals(Diff.overrides(SCRUM, REVIEW), Diff.overrides(SCRUM, Fixtures.reversed(REVIEW)));
    }

    @Test
    @DisplayName("a state without a name projects to a name UPDATE in either direction, never a failure")
    void projectionNullSafeName() {
        Map<String, Object> named = patch("{\"states\": {\"a\": {\"name\": \"A\"}}, \"stateOrder\": [\"a\"]}");
        Map<String, Object> unnamed = patch("{\"states\": {\"a\": {}}, \"stateOrder\": [\"a\"]}");
        assertEquals(List.of("UPDATE a name \"A\" -> null"), Diff.projection(named, unnamed));
        assertEquals(List.of("UPDATE a name null -> \"A\""), Diff.projection(unnamed, named));
    }
}
