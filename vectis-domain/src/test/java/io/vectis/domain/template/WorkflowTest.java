package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.eff;
import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Fixtures.reversed;
import static io.vectis.domain.template.Fixtures.states;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The workflow-definition cases of the VEC-66 spike, against the production code. */
class WorkflowTest {

    /** The transitions of a workflow definition, as "from>to" or "from>to[guard]". */
    static List<String> moves(Map<String, Object> definition) {
        return ((List<?>) definition.get("transitions")).stream().map(t -> {
            Map<String, Object> m = TemplateJson.obj(t);
            return m.get("from") + ">" + m.get("to") + (m.containsKey("guard") ? "[" + m.get("guard") + "]" : "");
        }).toList();
    }

    @Test
    @DisplayName("M5 workflow definition for the state-machine library: enterFrom becomes transitions, a gate becomes a named guard on every way out of it, ends carry their outcome")
    void m5() {
        Registry r = Registry.shipped();
        Map<String, Object> sg = r.res("stage-gate@1");
        Map<String, Object> flow = Workflow.definition(sg);
        List<String> moves = moves(flow);
        assertEquals(List.of("idea>scoping", "gate-1>scoping[gate:gate-1]", "scoping>gate-1", "gate-1>development[gate:gate-1]",
                "gate-2>development[gate:gate-2]", "development>gate-2", "gate-2>done[gate:gate-2]",
                "idea>no-go", "gate-1>no-go[gate:gate-1]", "gate-2>no-go[gate:gate-2]"), moves);
        assertFalse(moves.contains("idea>development"));
        assertEquals(List.of("idea"), flow.get("initial"));
        assertEquals(TemplateJson.parse("{\"end\": \"DELIVERED\"}"), TemplateJson.obj(flow.get("states")).get("done"));
        assertEquals(states(sg).keySet(), TemplateJson.obj(flow.get("states")).keySet());
        assertEquals(List.of("states", "initial", "transitions"), List.copyOf(flow.keySet()));
        assertEquals(flow, Workflow.definition(TemplateHierarchy.derive(r.res("base@1"),
                reversed(Registry.h("stage-gate.v1.json")), "x").effective()));
    }

    @Test
    @DisplayName("without enterFrom every other state is a source; a state never transitions to itself; no gate, no guard")
    void openWorkflow() {
        Map<String, Object> kanban = eff(load("vec45/kanban.v1.json"), Map.of());
        List<String> moves = moves(Workflow.definition(kanban));
        assertEquals(12, moves.size());
        assertTrue(moves.containsAll(List.of("todo>in-progress", "done>todo", "no-go>done")));
        assertTrue(moves.stream().noneMatch(m -> m.contains("[")));
        assertTrue(moves.stream().noneMatch(m -> m.split(">")[0].equals(m.split(">")[1])));
        assertEquals(TemplateJson.parse("{\"end\": \"DISCONTINUED\"}"), TemplateJson.obj(Workflow.definition(kanban).get("states")).get("no-go"));
    }

    @Test
    @DisplayName("every START_STATE is initial, in effective order; the definition follows the workspace's own states")
    void initialStates() {
        Map<String, Object> refined = eff(load("vec45/scrum.v1.json"), load("vec45/instance-refined.json"));
        Map<String, Object> flow = Workflow.definition(refined);
        assertEquals(List.of("backlog", "refined", "todo"), flow.get("initial"));
        Map<String, Object> narrow = eff(load("vec45/scrum.v1.json"), patch("{\"states\": {\"done\": {\"enterFrom\": [\"in-review\"]}}}"));
        assertEquals(List.of("in-review>done"), moves(Workflow.definition(narrow)).stream().filter(m -> m.endsWith(">done")).toList());
    }
}
