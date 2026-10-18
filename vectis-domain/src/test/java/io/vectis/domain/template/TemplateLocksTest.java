package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.has;
import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Registry.h;
import static io.vectis.domain.template.TemplateHierarchyTest.derived;
import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lock and gated-delivery cases of the VEC-66 spike ({@code HierarchyCases.java}, L1 to L5). */
class TemplateLocksTest {

    final Registry r = Registry.shipped();
    final Map<String, Object> sg = r.res("stage-gate@1");
    static final String END = "\"category\": \"END_STATE\", \"outcome\": \"DELIVERED\"";

    List<Violation> underStageGate(String sections) {
        return TemplateHierarchy.derive(sg, derived("t", 1, "stage-gate", 1, sections), "t@1").violations();
    }

    @Test
    @DisplayName("L1 stage-gate locks its gates and the entries into and out of them: a level below cannot drop or weaken a gate or rewire those entries, may rename it; locks accumulate")
    void l1() {
        assertTrue(has(underStageGate("\"states\": {\"gate-1\": {\"gate\": {\"approvals\": 1}}}"), "t@1", "states.gate-1.gate", "locked by an ancestor template ('states.gate-1.gate')"));
        assertTrue(has(underStageGate("\"states\": {\"gate-1\": null}"), "t@1", "states.gate-1", "locked by an ancestor template ('states.gate-1."));
        assertTrue(has(underStageGate("\"states\": {\"development\": {\"enterFrom\": [\"scoping\"]}}"), "t@1", "states.development.enterFrom", "locked"));
        assertEquals(List.of(), underStageGate("\"states\": {\"gate-1\": {\"name\": \"Investment decision\"}}"));
        assertTrue(has(TemplateHierarchy.lockViolations(strings(sg.get("locks")), patch("{\"states\": {\"done\": {\"enterFrom\": [\"development\"]}}}")),
                null, "states.done.enterFrom", "locked"));
        assertEquals(List.of("settings.gatedDelivery", "states.development.enterFrom", "states.done.enterFrom", "states.gate-1.enterFrom",
                        "states.gate-1.gate", "states.gate-2.enterFrom", "states.gate-2.gate", "states.idea"),
                strings(TemplateHierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"locks\": [\"states.idea\"]"), "t@1").effective().get("locks")));
        assertEquals(7, strings(TemplateHierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"locks\": []"), "t@1").effective().get("locks")).size());
    }

    @Test
    @DisplayName("L2 an organisation's lock binds every team and workspace below it: QA cannot be loosened, Done's entry cannot be widened")
    void l2() {
        Map<String, Object> platform = r.res("acme-platform@1");
        assertTrue(TemplateResolver.resolveWorkspace(platform, h("instance-platform.json")).ok());
        List<Violation> qa = TemplateResolver.resolveWorkspace(platform, patch("{\"states\": {\"qa\": {\"wipLimit\": 9}}}")).violations();
        assertTrue(qa.contains(new Violation("states.qa.wipLimit", TemplateResolver.WORKSPACE, "locked by an ancestor template ('states.qa')")), qa::toString);
        assertTrue(has(TemplateResolver.resolveWorkspace(platform, patch("{\"states\": {\"done\": {\"enterFrom\": [\"qa\", \"in-review\"]}}}")).violations(),
                TemplateResolver.WORKSPACE, "states.done.enterFrom", "locked"));
        assertTrue(TemplateResolver.resolveWorkspace(platform, patch("{\"states\": {\"in-review\": {\"name\": \"Peer Review\"}}}")).ok());
    }

    @Test
    @DisplayName("L3 no way round a lock: below member level or a misspelt member is refused; setting the gate object hits a lock on it; a stateOrder lock holds against anchors, additions and removals")
    void l3() {
        Map<String, Object> scrum = r.res("scrum@1");
        String gated = "\"states\": {\"in-review\": {\"gate\": {\"approvals\": 2}}}";
        assertTrue(has(TemplateHierarchy.derive(scrum, derived("org", 1, "scrum", 1, gated + ", \"locks\": [\"states.in-review.gate.approvals\"]"), "org@1").violations(),
                "org@1", "locks", "'states.in-review.gate.approvals' is deeper than a member"));
        assertTrue(has(TemplateHierarchy.derive(scrum, derived("org", 1, "scrum", 1, "\"locks\": [\"states.in-progress.wipLimt\"]"), "org@1").violations(),
                "org@1", "locks", "'states.in-progress.wipLimt' names a member states elements do not have"));
        Resolution org = TemplateHierarchy.derive(scrum, derived("org", 1, "scrum", 1, gated + ", \"locks\": [\"states.in-review.gate\", \"stateOrder\"]"), "org@1");
        assertTrue(org.ok(), org::toString);
        Map<String, Object> orgEff = org.effective();
        String xs = "\"category\": \"IN_PROGRESS\"";
        assertTrue(has(underOrg(orgEff, "\"states\": {\"in-review\": {\"gate\": {\"approvals\": 1}}}"), "t@1", "states.in-review.gate", "locked"));
        assertTrue(has(underOrg(orgEff, "\"states\": {\"done\": {\"before\": \"todo\"}}"), "t@1", "stateOrder", "locked by an ancestor template; its value would change"));
        assertTrue(has(underOrg(orgEff, "\"states\": {\"qa\": {\"name\": \"QA\", " + xs + ", \"after\": \"in-review\"}}"), "t@1", "stateOrder", "locked"));
        assertTrue(has(underOrg(orgEff, "\"states\": {\"backlog\": null}"), "t@1", "stateOrder", "locked"));
        assertTrue(has(underOrg(orgEff, "\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]"), "t@1", "stateOrder", "locked"));
        assertEquals(List.of(), underOrg(orgEff, "\"states\": {\"todo\": {\"name\": \"Next\"}}"));
        // A workspace too: an anchor touches no locked path, so only the locked-value check sees it.
        assertTrue(has(TemplateResolver.resolveWorkspace(orgEff, patch("{\"states\": {\"done\": {\"before\": \"todo\"}}}")).violations(),
                TemplateResolver.WORKSPACE, "stateOrder", "locked by an ancestor template; its value would change"));
    }

    private static List<Violation> underOrg(Map<String, Object> orgEff, String sections) {
        return TemplateHierarchy.derive(orgEff, derived("t", 1, "org", 1, sections), "t@1").violations();
    }

    @Test
    @DisplayName("L4 gated delivery: a level below stage-gate cannot open an ungated way to a DELIVERED end by a changed outcome, a recategorised stage or a new end state; one behind Gate 2 is fine; the invariant itself is locked")
    void l4() {
        assertTrue(has(underStageGate("\"states\": {\"no-go\": {\"name\": \"Shipped\", \"outcome\": \"DELIVERED\"}}"),
                "t@1", "settings.gatedDelivery", "'idea' reaches the DELIVERED end state 'no-go' without passing a locked gate"));
        assertTrue(has(underStageGate("\"states\": {\"scoping\": {" + END + "}}"), "t@1", "settings.gatedDelivery", "reaches the DELIVERED end state 'scoping'"));
        assertTrue(has(underStageGate("\"states\": {\"shipped\": {\"name\": \"Shipped\", " + END + ", \"after\": \"done\"}}"), "t@1", "settings.gatedDelivery", "reaches the DELIVERED end state 'shipped'"));
        assertEquals(List.of(), underStageGate("\"states\": {\"shipped\": {\"name\": \"Shipped\", " + END + ", \"after\": \"done\", \"enterFrom\": [\"gate-2\"]}}"));
        assertTrue(has(underStageGate("\"settings\": {\"gatedDelivery\": false}"), "t@1", "settings.gatedDelivery", "locked"));
        assertTrue(has(LevelResolver.resolve(TemplateHierarchy.strip(r.res("scrum@1")), patch("{\"settings\": {\"gatedDelivery\": true}}")).violations(),
                null, "settings.gatedDelivery", "'backlog' reaches the DELIVERED end state 'done'"));
    }

    @Test
    @DisplayName("L5 only the gates frozen where the rule became binding count; a level that enables and binds the rule counts the gates it locks itself")
    void l5() {
        String ownGate = "\"states\": {\"fast-track\": {\"name\": \"Fast track\", \"category\": \"IN_PROGRESS\", \"after\": \"scoping\", \"enterFrom\": [\"scoping\"], \"gate\": {\"approvals\": 1}},"
                + " \"shipped\": {\"name\": \"Shipped\", " + END + ", \"after\": \"done\", \"enterFrom\": [\"fast-track\"]}}, \"locks\": [\"states.fast-track.gate\"]";
        String scopingGate = "\"states\": {\"scoping\": {\"gate\": {\"approvals\": 1}},"
                + " \"shipped\": {\"name\": \"Shipped\", " + END + ", \"after\": \"done\", \"enterFrom\": [\"scoping\"]}}";
        String unlocked = "'idea' reaches the DELIVERED end state 'shipped' without passing a locked gate";
        assertTrue(has(underStageGate(ownGate), "t@1", "settings.gatedDelivery", unlocked));
        assertTrue(has(underStageGate(scopingGate), "t@1", "settings.gatedDelivery", unlocked));
        assertTrue(has(underStageGate("\"states\": {\"gate-2\": {\"enterFrom\": [\"idea\"]}}"), "t@1", "states.gate-2.enterFrom", "locked by an ancestor template"));
        assertTrue(has(underStageGate("\"states\": {\"gate-1\": {\"enterFrom\": [\"idea\"]}}"), "t@1", "states.gate-1.enterFrom", "locked by an ancestor template"));

        Map<String, Object> hybrid = r.res("hybrid@1");
        Map<String, Object> hybridGated = derived("hg", 1, "hybrid", 1, "\"settings\": {\"gatedDelivery\": true}, \"locks\": [\"states.release-review.gate\"]");
        Map<String, Object> hybridBinding = derived("hg", 1, "hybrid", 1,
                "\"settings\": {\"gatedDelivery\": true}, \"locks\": [\"states.release-review.gate\", \"settings.gatedDelivery\"]");
        assertTrue(TemplateHierarchy.derive(hybrid, hybridGated, "hg@1").ok());
        assertTrue(TemplateHierarchy.derive(hybrid, hybridBinding, "hg@1").ok());
        assertTrue(has(TemplateHierarchy.derive(TemplateHierarchy.derive(hybrid, hybridGated, "hg@1").effective(), derived("hgt", 1, "hg", 1,
                        "\"states\": {\"fast\": {\"name\": \"Fast\", \"category\": \"IN_PROGRESS\", \"after\": \"in-progress\", \"enterFrom\": [\"in-progress\"], \"gate\": {\"approvals\": 1}},"
                                + " \"hotfix\": {\"name\": \"Hotfix\", " + END + ", \"after\": \"done\", \"enterFrom\": [\"fast\"]}}, \"locks\": [\"states.fast.gate\"]"), "hgt@1").violations(),
                "hgt@1", "settings.gatedDelivery", "'backlog' reaches the DELIVERED end state 'hotfix' without passing a locked gate"));
        assertEquals(List.of("states.release-review.gate"), TemplateHierarchy.derive(hybrid, hybridBinding, "hg@1").effective().get("deliveryGates"));
        assertEquals(List.of("states.gate-1.gate", "states.gate-2.gate"), sg.get("deliveryGates"));

        Registry rt = Registry.shipped();
        assertEquals(List.of(), rt.publish("acme", derived("t1", 1, "stage-gate", 1,
                "\"states\": {\"scoping\": {\"gate\": {\"approvals\": 1}}}, \"locks\": [\"states.scoping.gate\"]")));
        String fromScoping = "\"states\": {\"shipped\": {\"name\": \"Shipped\", " + END + ", \"after\": \"done\", \"enterFrom\": [\"scoping\"]}}";
        assertTrue(has(rt.publish("acme", derived("t2", 1, "t1", 1, fromScoping)), "t2@1", "settings.gatedDelivery", unlocked));
        assertTrue(has(TemplateResolver.resolveWorkspace(rt.res("t1@1"), patch("{" + fromScoping + "}")).violations(), TemplateResolver.WORKSPACE, "settings.gatedDelivery", unlocked));
        assertTrue(has(rt.publish("acme", obj(TemplateJson.mergePatch(derived("t3", 1, "t1", 1, ""), patch("{\"deliveryGates\": [\"states.scoping.gate\"]}")))),
                "t3@1", "deliveryGates", "delta may not set 'deliveryGates'"));
        assertTrue(has(LevelResolver.resolve(TemplateHierarchy.strip(hybrid), patch("{\"settings\": {\"gatedDelivery\": true}}")).violations(),
                null, "settings.gatedDelivery", "'backlog' reaches the DELIVERED end state 'done' without passing a locked gate"));
    }
}
