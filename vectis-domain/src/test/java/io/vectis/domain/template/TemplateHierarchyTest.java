package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.eff;
import static io.vectis.domain.template.Fixtures.has;
import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.name;
import static io.vectis.domain.template.Fixtures.order;
import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Fixtures.reversed;
import static io.vectis.domain.template.Fixtures.state;
import static io.vectis.domain.template.Fixtures.states;
import static io.vectis.domain.template.Fixtures.types;
import static io.vectis.domain.template.Registry.h;
import static io.vectis.domain.template.TemplateJson.obj;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The N-level resolution, publishing and provenance cases of the VEC-66 spike
 * ({@code HierarchyCases.java}) and the methodology cases ({@code MethodologyCases.java}) that
 * need no write path, against the production code. Case ids are the spike's.
 */
class TemplateHierarchyTest {

    final Registry r = Registry.shipped();

    /** A derived document: identity plus a delta, as a level is authored. */
    static Map<String, Object> derived(String key, long version, String parent, long parentVersion, String sections) {
        return patch("{\"template\": \"" + key + "\", \"version\": " + version + ", \"name\": \"" + key + "\","
                + " \"extends\": {\"template\": \"" + parent + "\", \"version\": " + parentVersion + "}"
                + (sections.isBlank() ? "" : ", " + sections) + "}");
    }

    static Map<String, Object> cadence(Map<String, Object> eff) {
        return obj(eff.get("cadence"));
    }

    static Object parents(Map<String, Object> eff, String type) {
        return obj(types(eff).get(type)).get("parents");
    }

    @ParameterizedTest
    @ValueSource(strings = {"base.v1.json", "scrum.v1.json", "kanban.v1.json", "hybrid.v1.json", "template.schema.json",
            "hierarchy/stage-gate.v1.json", "hierarchy/acme.v1.json", "hierarchy/acme-platform.v1.json", "hierarchy/instance-platform.json"})
    @DisplayName("H1 (canonical) every shipped and fixture document is in canonical form")
    void h1Canonical(String file) {
        String bytes = Fixtures.text(file);
        assertEquals(bytes, TemplateJson.write(TemplateJson.parse(bytes)));
    }

    @Test
    @DisplayName("H1 every level of the shipped hierarchy publishes and resolves on its own; member order irrelevant")
    void h1() {
        assertEquals(7, r.versions.size());
        for (String f : List.of("scrum.v1.json", "hierarchy/stage-gate.v1.json", "hierarchy/acme.v1.json", "hierarchy/acme-platform.v1.json")) {
            Map<String, Object> doc = load(f);
            Map<String, Object> parent = r.res(obj(doc.get("extends")).get("template") + "@1");
            assertEquals(TemplateHierarchy.derive(parent, doc, "x").effective(),
                    TemplateHierarchy.derive(reversed(parent), reversed(doc), "x").effective(), f);
        }
        assertTrue(TemplateResolver.resolveWorkspace(r.res("acme-platform@1"), h("instance-platform.json")).ok());
    }

    @Test
    @DisplayName("H2 the hierarchy's scrum and kanban give VEC-45's single-level boards: order, states, item types, fields, settings")
    void h2() {
        assertSameBoard(r.res("scrum@1"), eff(load("vec45/scrum.v1.json"), Map.of()));
        assertSameBoard(r.res("kanban@1"), eff(load("vec45/kanban.v1.json"), Map.of()));
    }

    private static void assertSameBoard(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> x = TemplateHierarchy.strip(a);
        Map<String, Object> y = TemplateHierarchy.strip(b);
        Map<String, Object> ta = new TreeMap<>();
        Map<String, Object> tb = new TreeMap<>();
        types(x).forEach((k, t) -> ta.put(k, obj(t).get("name")));
        types(y).forEach((k, t) -> tb.put(k, obj(t).get("name")));
        assertEquals(y.get("states"), x.get("states"));
        assertEquals(order(y), order(x));
        assertEquals(tb, ta);
        assertEquals(y.get("fields"), x.get("fields"));
        assertEquals(y.get("settings"), x.get("settings"));
    }

    @Test
    @DisplayName("H3 anchors are level-local: scrum anchors In Review after In Progress, a child anchors On Hold there too; no collision, the child's is nearest")
    void h3() {
        Map<String, Object> hold = derived("hold", 1, "scrum", 1,
                "\"states\": {\"on-hold\": {\"name\": \"On Hold\", \"category\": \"IN_PROGRESS\", \"after\": \"in-progress\"}}");
        Resolution h3 = TemplateHierarchy.derive(r.res("scrum@1"), hold, "hold@1");
        assertTrue(h3.ok(), h3::toString);
        assertEquals(List.of("backlog", "todo", "in-progress", "on-hold", "in-review", "done", "no-go"), order(h3.effective()));
        assertFalse(state(h3.effective(), "in-review").containsKey("after"));
        assertTrue(has(LevelResolver.resolve(r.res("scrum@1"), TemplateHierarchy.sections(hold)).violations(), null, "states.on-hold.after", "states.in-review is already placed after 'in-progress'"));
    }

    @Test
    @DisplayName("H4 pinned at every level: base@2 and scrum@2 change nothing pinned to acme-platform@1; every materialised resolution equals re-resolving its chain")
    void h4() {
        Map<String, Object> pinned = obj(TemplateJson.deepCopy(r.res("acme-platform@1")));
        assertEquals(List.of(), r.publish(TemplateResolver.SYSTEM, obj(TemplateJson.mergePatch(load("base.v1.json"),
                patch("{\"version\": 2, \"states\": {\"todo\": {\"name\": \"Ready\"}}}")))));
        String scrumDelta = TemplateJson.compact(TemplateHierarchy.sections(load("scrum.v1.json"))).replaceAll("^\\{|\\}$", "");
        assertEquals(List.of(), r.publish(TemplateResolver.SYSTEM, derived("scrum", 2, "base", 2, scrumDelta)));
        assertEquals(pinned, r.res("acme-platform@1"));
        assertEquals("Ready", name(r.res("scrum@2"), "todo"));
        assertEquals("To Do", name(r.res("acme-platform@1"), "todo"));
        for (TemplateVersion x : r.versions) {
            assertEquals(r.res(x.ref()), TemplateHierarchy.resolveChain(r.chain(x.key(), x.version())).effective(), x.ref());
        }
    }

    @Test
    @DisplayName("H5 publish refuses: a re-published version, an unknown parent, another tenant's parent or key, a tenant root, no name, too many levels, a level that does not resolve")
    void h5() {
        assertEquals(List.of(), r.publish("acme", derived("d1", 1, "acme-platform", 1, "")));   // base > scrum > acme > acme-platform > d1: five, the limit
        assertTrue(has(r.publish(TemplateResolver.SYSTEM, load("scrum.v1.json")), "scrum@1", "version", "scrum is at version 1; a new version is 2"));
        assertTrue(has(r.publish("acme", derived("x", 1, "scrum", 9, "")), "x@1", "extends", "scrum@9 is not a published template version"));
        assertTrue(has(r.publish("globex", derived("g", 1, "acme", 1, "")), "g@1", "extends", "acme@1 belongs to another owner"));
        assertTrue(has(r.publish("globex", derived("acme", 2, "scrum", 1, "")), "acme@2", "template", "'acme' belongs to acme"));
        assertTrue(has(r.publish("globex", obj(TemplateJson.mergePatch(load("base.v1.json"), patch("{\"template\": \"mine\"}")))), "mine@1", "extends", "roots are built-in"));
        assertTrue(has(r.publish("acme", obj(TemplateJson.mergePatch(derived("n", 1, "scrum", 1, ""), patch("{\"name\": null}")))),
                "n@1", "name", "a template version needs its own name"));
        assertEquals(1, r.latest("d1"));
        assertTrue(has(r.publish("acme", derived("d2", 1, "d1", 1, "")), "d2@1", "extends", "more than 5 template levels"));
        assertTrue(has(r.publish("acme", derived("y", 1, "acme", 1, "\"states\": {\"qa\": {\"enterFrom\": [\"ghost\"]}}")), "y@1", "states.qa.enterFrom", "locked"));
        assertTrue(has(r.publish("acme", derived("z", 1, "acme", 1, "\"itemTypes\": {\"story\": null}")),
                "z@1", "settings.defaultItemType", "references unknown item type"));
    }

    @Test
    @DisplayName("H6 provenance: every effective value names the level that last set it, root to workspace")
    void h6() {
        Resolution res = TemplateResolver.resolve(r.documents("acme-platform", 1), h("instance-platform.json"));
        assertTrue(res.ok(), res::toString);
        Map<String, String> prov = res.provenance();
        assertEquals("base@1", prov.get("states.todo.name"));
        assertEquals("acme@1", prov.get("states.in-review.name"));
        assertEquals("scrum@1", prov.get("states.in-review.category"));
        assertEquals("acme-platform@1", prov.get("states.refined.name"));
        assertEquals("scrum@1", prov.get("cadence.mode"));
        assertEquals("acme-platform@1", prov.get("cadence.sprintDays"));
        assertEquals(TemplateResolver.WORKSPACE, prov.get("states.in-progress.wipLimit"));
        assertFalse(prov.containsKey("itemTypes.bug.name"));
        assertEquals("acme@1", prov.get("itemTypes.task.parents"));
        assertEquals(TemplateResolver.resolveWorkspace(r.res("acme-platform@1"), h("instance-platform.json")).effective(), res.effective());
    }

    @Test
    @DisplayName("H7 (hierarchy) a description survives resolution of a derived level")
    void h7() {
        assertEquals(load("hybrid.v1.json").get("description"), r.res("hybrid@1").get("description"));
    }

    @Test
    @DisplayName("S5 the published JSON Schema and the validator accept the same members, section by section")
    void s5() {
        Map<String, Object> schema = load("template.schema.json");
        Map<String, Object> defs = obj(schema.get("$defs"));
        assertEquals(TemplateValidator.MEMBERS.get("document"), List.copyOf(obj(schema.get("properties")).keySet()));
        for (String kind : List.of("states", "itemTypes", "fields", "settings", "cadence", "gate")) {
            assertEquals(TemplateValidator.MEMBERS.get(kind), List.copyOf(obj(obj(defs.get(kind)).get("properties")).keySet()), kind);
        }
    }

    @Test
    @DisplayName("chain: a cycle, a missing parent, a chain deeper than five or out of order is refused with the level at fault")
    void chainShape() {
        Map<String, Object> a = derived("a", 1, "b", 1, "");
        Map<String, Object> b = derived("b", 1, "a", 1, "");
        assertEquals(List.of(new Violation("extends", "a@1", "the chain loops back to a@1")),
                TemplateResolver.resolve(List.of(b, a), null).violations());
        assertEquals(List.of(new Violation("extends", "scrum@1", "base@1 is not a published template version")),
                TemplateResolver.resolve(List.of(load("scrum.v1.json")), null).violations());
        List<Map<String, Object>> six = new ArrayList<>(r.documents("acme-platform", 1));
        six.add(derived("d1", 1, "acme-platform", 1, ""));
        six.add(derived("d2", 1, "d1", 1, ""));
        assertEquals(List.of(new Violation("extends", "d2@1", "more than 5 template levels, root included")),
                TemplateResolver.resolve(six, null).violations());
        List<Map<String, Object>> shuffled = new ArrayList<>(r.documents("acme", 1));
        shuffled.add(1, load("kanban.v1.json"));
        assertTrue(has(TemplateResolver.resolve(shuffled, null).violations(), "acme@1", "extends", "not root first"));
        assertTrue(TemplateResolver.resolve(r.documents("acme-platform", 1), null).ok());
    }

    @Test
    @DisplayName("publish refuses a candidate whose document names another key or version than the one it is published as")
    void publishIdentity() {
        TemplateVersion candidate = new TemplateVersion("acme2", 1, "acme", derived("other", 7, "scrum", 1, ""));
        List<Violation> v = TemplateResolver.publish(candidate, r).violations();
        assertTrue(has(v, "acme2@1", "template", "the document names template \"other\"; it is published as acme2@1"), v::toString);
        assertTrue(has(v, "acme2@1", "version", "the document names version 7; it is published as acme2@1"), v::toString);
        assertTrue(TemplateResolver.publish(TemplateVersion.of("acme", derived("acme2", 1, "scrum", 1, "")), r).ok());
    }

    @Test
    @DisplayName("extends is an object naming a template key and a version from 1, and nothing else: a string or any other shape is refused")
    void extendsShape() {
        for (String e : List.of("\"base@1\"", "{\"template\": \"base\"}", "{\"template\": \"base\", \"version\": 0}",
                "{\"template\": \"base\", \"version\": 1, \"note\": \"x\"}", "{\"template\": \"Base\", \"version\": 1}")) {
            Map<String, Object> scrum = obj(TemplateJson.mergePatch(load("scrum.v1.json"), patch("{\"extends\": null}")));
            scrum.put("extends", TemplateJson.parse(e));
            List<Violation> v = TemplateResolver.resolve(List.of(load("base.v1.json"), scrum), null).violations();
            assertEquals(List.of(new Violation("extends", "scrum@1", TemplateHierarchy.EXTENDS_SHAPE)), v, e);
            assertTrue(has(TemplateResolver.publish(TemplateVersion.of(TemplateResolver.SYSTEM, obj(TemplateJson.mergePatch(scrum,
                    patch("{\"template\": \"s2\"}")))), r).violations(), "s2@1", "extends", TemplateHierarchy.EXTENDS_SHAPE), e);
        }
    }

    @Test
    @DisplayName("deliveryGates is set by resolution: a root that authors it is refused")
    void rootAuthoredDeliveryGates() {
        Map<String, Object> base = obj(TemplateJson.mergePatch(load("base.v1.json"), patch("{\"deliveryGates\": [\"states.done\"]}")));
        assertEquals(List.of(new Violation("deliveryGates", "base@1", "set by resolution, never authored")),
                TemplateResolver.resolve(List.of(base), null).violations());
    }

    @Test
    @DisplayName("provenance: a field a later level unsets loses its entry; its element's other fields keep theirs")
    void provenanceOfAnUnsetField() {
        List<Map<String, Object>> chain = List.of(load("base.v1.json"), load("scrum.v1.json"),
                derived("w", 1, "scrum", 1, "\"states\": {\"in-progress\": {\"wipLimit\": 3}}"));
        assertEquals("w@1", TemplateResolver.resolve(chain, null).provenance().get("states.in-progress.wipLimit"));
        Resolution unset = TemplateResolver.resolve(chain, patch("{\"states\": {\"in-progress\": {\"wipLimit\": null}}}"));
        assertTrue(unset.ok(), unset::toString);
        assertFalse(unset.provenance().containsKey("states.in-progress.wipLimit"));
        assertEquals("base@1", unset.provenance().get("states.in-progress.name"));
    }

    @Test
    @DisplayName("M1 Scrum: two-week sprints, epic > story or bug > task, Fibonacci points, Backlog before To Do")
    void m1() {
        Map<String, Object> scrum = r.res("scrum@1");
        assertEquals("sprint", cadence(scrum).get("mode"));
        assertEquals(14L, cadence(scrum).get("sprintDays"));
        assertEquals(List.of("epic"), parents(scrum, "story"));
        assertEquals(List.of("story", "bug"), parents(scrum, "task"));
        assertNull(parents(scrum, "epic"));
        assertEquals("fibonacci", obj(obj(scrum.get("fields")).get("storyPoints")).get("scale"));
        assertEquals("backlog", order(scrum).getFirst());
    }

    @Test
    @DisplayName("M2 Kanban: continuous flow inherited from the base, a WIP limit on In Progress, no point field, no sprint length")
    void m2() {
        Map<String, Object> kanban = r.res("kanban@1");
        assertEquals("flow", cadence(kanban).get("mode"));
        assertFalse(cadence(kanban).containsKey("sprintDays"));
        assertEquals(5L, state(kanban, "in-progress").get("wipLimit"));
        assertTrue(obj(kanban.get("fields")).isEmpty());
        assertEquals(List.of("epic"), parents(kanban, "task"));
    }

    @Test
    @DisplayName("M3 (resolution) Hybrid, three levels deep: sprints from Scrum, phase > epic > story, a release review before Released")
    void m3() {
        Map<String, Object> hybrid = r.res("hybrid@1");
        assertEquals("sprint", cadence(hybrid).get("mode"));
        assertEquals(14L, cadence(hybrid).get("sprintDays"));
        assertEquals(List.of("phase"), parents(hybrid, "epic"));
        assertNull(parents(hybrid, "phase"));
        assertEquals(List.of("backlog", "todo", "in-progress", "in-review", "release-review", "done", "no-go"), order(hybrid));
        assertEquals("Released", name(hybrid, "done"));
        assertEquals("DELIVERED", state(hybrid, "done").get("outcome"));
        assertEquals(3, r.chain("hybrid", 1).size());
    }

    @Test
    @DisplayName("M4 (resolution) Stage-gate: phases not sprints; stages and gates in order; Base's To Do and In Progress removed; project > deliverable > task, milestones")
    void m4() {
        Map<String, Object> sg = r.res("stage-gate@1");
        assertEquals("phase", cadence(sg).get("mode"));
        assertFalse(cadence(sg).containsKey("sprintDays"));
        assertEquals(List.of("idea", "scoping", "gate-1", "development", "gate-2", "done", "no-go"), order(sg));
        assertFalse(states(sg).containsKey("todo"));
        assertEquals("Launched", name(sg, "done"));
        assertEquals("Killed", name(sg, "no-go"));
        assertEquals(patch("{\"approvals\": 2}"), state(sg, "gate-1").get("gate"));
        assertEquals(List.of("deliverable"), parents(sg, "task"));
        assertEquals(List.of("project"), parents(sg, "milestone"));
        assertEquals("project", obj(sg.get("settings")).get("defaultItemType"));
    }

    @Test
    @DisplayName("M6/M7 run in LevelResolverTest on a one-level stand-in for scrum@1; the stand-in is the hierarchy's scrum@1, section by section")
    void m6m7StandIn() {
        Map<String, Object> scrum = TemplateHierarchy.strip(r.res("scrum@1"));
        for (String s : List.of("states", "stateOrder", "itemTypes", "fields", "settings", "cadence")) {
            assertEquals(scrum.get(s), LevelResolverTest.SCRUM_WITH_PARENTS.get(s), s);
        }
    }

    @Test
    @Disabled("VEC-92: the workflow definition; assert release-review>done[gate:release-review] is the only way to done")
    @DisplayName("M3 (workflow) Hybrid: the release gate is the only way to Released")
    void m3Workflow() {
    }

    @Test
    @Disabled("VEC-92: the import fallback; assert idea for START_STATE and no-go for END_STATE/DISCONTINUED")
    @DisplayName("M4 (fallback) Stage-gate: an unrecognised status lands in Idea, or in Killed when discontinued")
    void m4Fallback() {
    }

    @Test
    @Disabled("VEC-92: the workflow definition; assert the four sequential moves and verify>done[gate:verify] with the sign-off gate")
    @DisplayName("M9 (workflow) waterfall: stages entered strictly in sequence; a sign-off gate guards the way to Released")
    void m9Workflow() {
    }

    @Test
    @DisplayName("M9 (resolution) waterfall maps without a new concept: phase cadence, stages entered strictly in sequence; a sign-off gate with gated delivery also resolves")
    void m9() {
        String seq = "\"states\": {\"todo\": null, \"in-progress\": null,"
                + " \"requirements\": {\"name\": \"Requirements\", \"category\": \"START_STATE\", \"enterFrom\": []},"
                + " \"design\": {\"name\": \"Design\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"requirements\"]},"
                + " \"build\": {\"name\": \"Build\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"design\"]},"
                + " \"verify\": {\"name\": \"Verify\", \"category\": \"IN_PROGRESS\", \"enterFrom\": [\"build\"]GATE},"
                + " \"done\": {\"name\": \"Released\", \"enterFrom\": [\"verify\"]}},"
                + " \"stateOrder\": [\"requirements\", \"design\", \"build\", \"verify\", \"done\", \"no-go\"], \"cadence\": {\"mode\": \"phase\"}";
        Resolution waterfall = TemplateHierarchy.derive(r.res("base@1"), derived("waterfall", 1, "base", 1, seq.replace("GATE", "")), "waterfall@1");
        Resolution signedOff = TemplateHierarchy.derive(r.res("base@1"), derived("waterfall", 1, "base", 1,
                seq.replace("GATE", ", \"gate\": {\"approvals\": 1}") + ", \"settings\": {\"gatedDelivery\": true}, \"locks\": [\"states.verify.gate\"]"), "waterfall@1");
        assertTrue(waterfall.ok(), waterfall::toString);
        assertTrue(signedOff.ok(), signedOff::toString);
        assertEquals("phase", cadence(waterfall.effective()).get("mode"));
    }
}
