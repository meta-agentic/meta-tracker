package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.has;
import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.patch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The delta-write cases of the VEC-45 spike ({@code docs/spikes/template-instance-model/}),
 * against the production code. Case ids are the spike's; a case whose resolution half VEC-90
 * ports keeps only its write half here.
 */
class DeltaWriterTest {

    static final Map<String, Object> SCRUM = load("vec45/scrum.v1.json");
    static final Map<String, Object> REFINED = load("vec45/instance-refined.json");
    static final Map<String, Object> REVIEW = load("vec45/instance-review.json");
    static final Map<String, Object> NONE = Map.of();
    static final Map<String, Integer> EMPTY = Map.of();
    static final Map<String, Object> DROP_BACKLOG = patch("{\"states\": {\"backlog\": null}}");

    @Test
    @DisplayName("S3/S4 the worked instances are accepted as delta writes over a fresh workspace")
    void s3s4() {
        assertEquals(List.of(), DeltaWriter.write(SCRUM, NONE, REFINED, EMPTY).violations());
        assertEquals(List.of(), DeltaWriter.write(SCRUM, NONE, REVIEW, EMPTY).violations());
    }

    @Test
    @DisplayName("I3 remove an empty state: allowed")
    void i3() {
        Resolution r = DeltaWriter.write(SCRUM, NONE, DROP_BACKLOG, EMPTY);
        assertTrue(r.ok(), r::toString);
        assertFalse(Fixtures.states(r.effective()).containsKey("backlog"));
    }

    @Test
    @DisplayName("I4 remove an occupied state: rejected, as a typed violation on the state")
    void i4() {
        List<Violation> v = DeltaWriter.write(SCRUM, NONE, DROP_BACKLOG, Map.of("states.backlog", 12)).violations();
        assertEquals(List.of(new Violation("states.backlog", null, "still used by 12 item(s); move or retype them first")), v);
    }

    @Test
    @DisplayName("I6 remove an item type still used by items: rejected")
    void i6() {
        List<Violation> v = DeltaWriter.write(SCRUM, NONE, REVIEW, Map.of("itemTypes.bug", 3)).violations();
        assertTrue(has(v, "itemTypes.bug", "still used by 3"), v::toString);
    }

    @Test
    @DisplayName("I10 stateOrder listing an unknown state: rejected at the write")
    void i10() {
        List<Violation> v = DeltaWriter.write(SCRUM, NONE, patch("{\"stateOrder\": [\"ghost\"]}"), EMPTY).violations();
        assertTrue(v.contains(new Violation("stateOrder", null, "references unknown state 'ghost'")), v::toString);
    }

    @Test
    @DisplayName("a removal is checked against the old delta: an element the old delta already removed is not removed again")
    void removalIsRelativeToTheOldDelta() {
        assertTrue(DeltaWriter.write(SCRUM, DROP_BACKLOG, DROP_BACKLOG, Map.of("states.backlog", 12)).ok());
        assertTrue(DeltaWriter.write(SCRUM, REFINED, NONE, Map.of("states.refined", 0)).ok());
        assertTrue(has(DeltaWriter.write(SCRUM, REFINED, NONE, Map.of("states.refined", 1)).violations(),
                "states.refined", "still used by 1"));
    }

    @Test
    @DisplayName("a delta that does not resolve is not also reported as orphaning items")
    void removalsOnlyOnAResolvingDelta() {
        List<Violation> v = DeltaWriter.write(SCRUM, NONE, patch("{\"states\": {\"backlog\": null, \"todo\": 5}}"),
                Map.of("states.backlog", 12)).violations();
        assertTrue(has(v, "states.todo", "must be an object"), v::toString);
        assertTrue(v.stream().noneMatch(x -> x.message().contains("still used by")), v::toString);
    }

    @Test
    @DisplayName("narrowing an item type's parents is refused only while links sit under a dropped parent")
    void narrowedParents() {
        Map<String, Object> before = patch("{\"itemTypes\": {\"task\": {\"parents\": [\"story\", \"bug\"]}}}");
        Map<String, Object> after = patch("{\"itemTypes\": {\"task\": {\"parents\": [\"story\"]}}}");
        assertEquals(List.of(new Violation("itemTypes.task.parents", null,
                        "no longer allows 'bug' while 12 task item(s) sit under one; move them first")),
                DeltaWriter.checkRemovals(before, after, Map.of("itemTypes.task.parents.bug", 12)));
        assertEquals(List.of(), DeltaWriter.checkRemovals(before, after, Map.of("itemTypes.task.parents.story", 12)));
        assertEquals(List.of(), DeltaWriter.checkRemovals(before, after, EMPTY));
    }

    @Test
    @DisplayName("a write is pure: neither delta is modified")
    void pure() {
        Map<String, Object> review = load("vec45/instance-review.json");
        DeltaWriter.write(SCRUM, REFINED, review, EMPTY);
        assertEquals(load("vec45/instance-review.json"), review);
        assertEquals(load("vec45/instance-refined.json"), REFINED);
    }

    // ---- over the template hierarchy (VEC-66) ---------------------------

    static final Registry SHIPPED = Registry.shipped();

    @Test
    @DisplayName("M8 narrowing an item type's parents is refused at a delta write while items sit under a parent it drops; allowed when none do")
    void m8() {
        Map<String, Object> base = TemplateHierarchy.strip(SHIPPED.res("scrum@1"));
        Map<String, Object> narrowed = patch("{\"itemTypes\": {\"task\": {\"parents\": [\"story\"]}}}");
        List<Violation> v = DeltaWriter.write(base, NONE, narrowed, Map.of("itemTypes.task.parents.bug", 12)).violations();
        assertTrue(has(v, "itemTypes.task.parents", "no longer allows 'bug' while 12 task item(s) sit under one"), v::toString);
        assertTrue(DeltaWriter.write(base, NONE, narrowed, EMPTY).ok());
    }

    @Test
    @DisplayName("a workspace write refuses a locked key, attributed to the workspace; another field of the same state is free")
    void lockedKey() {
        Map<String, Object> stageGate = SHIPPED.res("stage-gate@1");
        List<Violation> v = DeltaWriter.writeWorkspace(stageGate, NONE,
                patch("{\"states\": {\"gate-1\": {\"gate\": {\"approvals\": 1}}}}"), EMPTY).violations();
        assertTrue(v.contains(new Violation("states.gate-1.gate", TemplateResolver.WORKSPACE,
                "locked by an ancestor template ('states.gate-1.gate')")), v::toString);
        assertTrue(has(DeltaWriter.writeWorkspace(stageGate, NONE, patch("{\"states\": {\"gate-1\": null}}"), EMPTY).violations(),
                TemplateResolver.WORKSPACE, "states.gate-1", "locked by an ancestor template"));
        Resolution renamed = DeltaWriter.writeWorkspace(stageGate, NONE, patch("{\"states\": {\"gate-1\": {\"name\": \"Go/kill\"}}}"), EMPTY);
        assertTrue(renamed.ok(), renamed::toString);
        assertEquals("Go/kill", Fixtures.name(renamed.effective(), "gate-1"));
    }

    @Test
    @DisplayName("a workspace write runs the write rules too: an occupied removal or an unknown stateOrder key is refused, once each")
    void workspaceWriteRules() {
        Map<String, Object> scrum = SHIPPED.res("scrum@1");
        List<Violation> v = DeltaWriter.writeWorkspace(scrum, NONE, DROP_BACKLOG, Map.of("states.backlog", 2)).violations();
        assertEquals(List.of(new Violation("states.backlog", TemplateResolver.WORKSPACE, "still used by 2 item(s); move or retype them first")), v);
        List<Violation> ghost = DeltaWriter.writeWorkspace(scrum, NONE, patch("{\"stateOrder\": [\"ghost\"]}"), EMPTY).violations();
        assertEquals(1, ghost.stream().filter(x -> x.message().contains("unknown state 'ghost'")).count(), ghost::toString);
        assertTrue(DeltaWriter.writeWorkspace(scrum, NONE, REFINED, EMPTY).ok());
    }

    @Test
    @DisplayName("a workspace write reports a violation found by both its resolution and its write rules once")
    void workspaceWriteDeduplicates() {
        List<Violation> v = DeltaWriter.writeWorkspace(SHIPPED.res("scrum@1"), NONE, patch("{\"states\": {\"todo\": 5}}"), EMPTY).violations();
        assertEquals(1, v.stream().filter(x -> x.field().equals("states.todo")).count(), v::toString);
        assertTrue(has(v, TemplateResolver.WORKSPACE, "states.todo", "must be an object"), v::toString);
    }
}
