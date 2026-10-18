package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.eff;
import static io.vectis.domain.template.Fixtures.has;
import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.name;
import static io.vectis.domain.template.Fixtures.order;
import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Fixtures.reversed;
import static io.vectis.domain.template.Fixtures.state;
import static io.vectis.domain.template.Fixtures.types;
import static io.vectis.domain.template.Fixtures.violations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The single-level resolution and validation cases of the VEC-45 spike
 * ({@code docs/spikes/template-instance-model/TemplateModelSpike.java}), against the production
 * code. Case ids are the spike's.
 *
 * <p>S5 (the published JSON Schema and the validator agree on members) is deferred to VEC-91,
 * which ships {@code template.schema.json} in the domain's resources.
 */
class LevelResolverTest {

    static final Map<String, Object> SCRUM = load("vec45/scrum.v1.json");
    static final Map<String, Object> KANBAN = load("vec45/kanban.v1.json");
    static final Map<String, Object> REFINED = load("vec45/instance-refined.json");
    static final Map<String, Object> REVIEW = load("vec45/instance-review.json");
    static final Map<String, Object> NONE = Map.of();
    static final String START = "\"category\": \"START_STATE\"";
    static final String END = "\"category\": \"END_STATE\"";

    @ParameterizedTest
    @ValueSource(strings = {"vec45/kanban.v1.json", "vec45/scrum.v1.json", "vec45/instance-refined.json", "vec45/instance-review.json"})
    @DisplayName("S1 the fixture is in canonical form")
    void s1(String file) {
        String bytes = Fixtures.text(file);
        assertEquals(bytes, TemplateJson.write(TemplateJson.parse(bytes)));
    }

    @Test
    @DisplayName("S2 kanban v1 and scrum v1 resolve cleanly with an empty delta")
    void s2() {
        assertEquals(List.of(), LevelResolver.resolve(KANBAN, NONE).violations());
        assertEquals(List.of(), LevelResolver.resolve(SCRUM, NONE).violations());
    }

    @Test
    @DisplayName("S3 the Refined instance resolves cleanly: Refined between Backlog and To Do, no stateOrder override")
    void s3() {
        Resolution worked = LevelResolver.resolve(SCRUM, REFINED);
        assertTrue(worked.ok(), worked::toString);
        assertFalse(REFINED.containsKey("stateOrder"));
        assertEquals(List.of("backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"), order(worked.effective()));
        assertEquals("START_STATE", state(worked.effective(), "refined").get("category"));
    }

    @Test
    @DisplayName("S4 the review instance resolves cleanly, QA after the renamed review column, bug removed")
    void s4() {
        Resolution second = LevelResolver.resolve(SCRUM, REVIEW);
        assertTrue(second.ok(), second::toString);
        assertEquals(List.of("backlog", "todo", "in-progress", "in-review", "qa", "done", "no-go"), order(second.effective()));
        assertEquals("Code Review", name(second.effective(), "in-review"));
        assertFalse(types(second.effective()).containsKey("bug"));
        assertEquals(List.of("qa"), state(second.effective(), "done").get("enterFrom"));
    }

    @Test
    @DisplayName("I2 add a state: unplaced is rejected (jsonb keeps no member order); after, before or stateOrder place it")
    void i2() {
        List<Violation> unplaced = violations(SCRUM, "{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\"},"
                + " \"uat\": {\"name\": \"UAT\", \"category\": \"IN_PROGRESS\"}}}");
        assertTrue(has(unplaced, "states.qa", "an added state must be placed"), unplaced::toString);
        assertTrue(has(unplaced, "states.uat", "an added state must be placed"), unplaced::toString);
        Map<String, Object> inbox = patch("{\"states\": {\"inbox\": {\"name\": \"Inbox\", " + START + ", \"before\": \"backlog\"}}}");
        assertTrue(LevelResolver.resolve(SCRUM, inbox).ok());
        assertEquals("inbox", order(eff(SCRUM, inbox)).getFirst());
        Map<String, Object> listedQa = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"category\": \"IN_PROGRESS\"}},"
                + " \"stateOrder\": [\"backlog\", \"todo\", \"in-progress\", \"qa\", \"in-review\", \"done\", \"no-go\"]}");
        assertTrue(LevelResolver.resolve(SCRUM, listedQa).ok());
        assertEquals(3, order(eff(SCRUM, listedQa)).indexOf("qa"));
    }

    @Test
    @DisplayName("I7 remove the default item type without re-pointing the default: rejected; re-pointed: allowed")
    void i7() {
        assertTrue(has(violations(SCRUM, "{\"itemTypes\": {\"story\": null}}"), "settings.defaultItemType", "references unknown item type \"story\""));
        assertTrue(LevelResolver.resolve(SCRUM, patch("{\"itemTypes\": {\"story\": null}, \"settings\": {\"defaultItemType\": \"task\"}}")).ok());
    }

    @Test
    @DisplayName("I8 delta outside its sections, tombstone of an unknown key, incomplete new state, badly shaped section: rejected")
    void i8() {
        assertTrue(has(violations(SCRUM, "{\"name\": \"Mine\"}"), "name", "delta may not set 'name'"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"nope\": null}}"), "states.nope", "removes an element the template does not have"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"qa\": {\"name\": \"QA\", \"after\": \"todo\"}}}"), "states.qa.category", "must be one of"));
        assertTrue(has(violations(SCRUM, "{\"fields\": null}"), "fields", "must be an object; a whole section cannot be removed"));
        assertEquals(SCRUM.get("fields"), eff(SCRUM, patch("{\"fields\": null}")).get("fields"));
        assertTrue(has(violations(SCRUM, "{\"settings\": \"x\"}"), "settings", "must be an object"));
        assertTrue(has(violations(SCRUM, "{\"stateOrder\": {\"a\": 1}}"), "stateOrder", "must be an array of state keys"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"todo\": 5}}"), "states.todo", "must be an object, or null to remove it"));
    }

    @Test
    @DisplayName("I9 remove a state another state's policy references: rejected")
    void i9() {
        assertTrue(has(violations(SCRUM, "{\"states\": {\"in-review\": null, \"done\": {\"enterFrom\": [\"in-review\"]}}}"),
                "states.done.enterFrom", "references unknown state 'in-review'"));
    }

    @Test
    @DisplayName("I10 stateOrder listing a duplicate state: rejected (the unknown-state half is a delta-write rule)")
    void i10() {
        assertTrue(has(violations(SCRUM, "{\"stateOrder\": [\"todo\", \"todo\"]}"), "stateOrder", "lists a state twice"));
    }

    @Test
    @DisplayName("I11 an anchor naming a removed state, a cycle, two states on one side of an anchor, both anchors, or also listed in stateOrder: rejected")
    void i11() {
        String x = START;
        assertTrue(has(violations(SCRUM, "{\"states\": {\"backlog\": null, \"refined\": {\"name\": \"R\", " + x + ", \"after\": \"backlog\"}}}"),
                "states.refined.after", "references unknown state 'backlog'"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"b\"},"
                + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"a\"}}}"), "states.a.after", "placement cycle through 'b'"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"todo\"},"
                + " \"b\": {\"name\": \"B\", " + x + ", \"after\": \"todo\"}}}"), "states.b.after", "states.a is already placed after 'todo'"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"before\": \"todo\"},"
                + " \"b\": {\"name\": \"B\", " + x + ", \"before\": \"todo\"}}}"), "states.b.before", "states.a is already placed before 'todo'"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"a\": {\"name\": \"A\", " + x + ", \"after\": \"todo\", \"before\": \"done\"}}}"), "states.a", "set 'after' or 'before', not both"));
        Map<String, Object> listed = TemplateJson.obj(TemplateJson.mergePatch(REFINED, patch("{\"stateOrder\": [\"backlog\","
                + " \"refined\", \"todo\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]}")));
        assertTrue(has(LevelResolver.resolve(SCRUM, listed).violations(), "stateOrder", "lists 'refined', which is placed by states.refined.after"));
    }

    @Test
    @DisplayName("I12 resolution ignores member order (jsonb keeps none): each delta, members reversed, resolves to the same order and content")
    void i12() {
        assertEquals(eff(SCRUM, REFINED), eff(SCRUM, reversed(REFINED)));
        assertEquals(order(eff(SCRUM, REFINED)), order(eff(SCRUM, reversed(REFINED))));
        assertEquals(eff(SCRUM, REVIEW), eff(SCRUM, reversed(REVIEW)));
        Map<String, Object> many = patch("{\"states\": {\"a\": {\"name\": \"A\", " + START + ", \"after\": \"todo\"},"
                + " \"b\": {\"name\": \"B\", " + START + ", \"after\": \"a\"}, \"c\": {\"name\": \"C\", \"category\": \"IN_PROGRESS\","
                + " \"before\": \"done\"}}}");
        assertTrue(LevelResolver.resolve(SCRUM, many).ok());
        assertEquals(List.of("backlog", "todo", "a", "b", "in-progress", "in-review", "c", "done", "no-go"), order(eff(SCRUM, many)));
        assertEquals(order(eff(SCRUM, many)), order(eff(SCRUM, reversed(many))));
    }

    @Test
    @DisplayName("D2 a discontinued end state is no delivery: dropping the only DELIVERED one is rejected; onBoard must be a boolean, and some state on the board")
    void d2() {
        String off = "{\"states\": {\"todo\": {\"onBoard\": false}, \"in-progress\": {\"onBoard\": false}";
        assertTrue(has(violations(SCRUM, "{\"states\": {\"done\": null}}"), "states", "needs at least one START_STATE state and one END_STATE state with outcome DELIVERED"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"no-go\": {\"onBoard\": \"no\"}}}"), "states.no-go.onBoard", "must be true or false"));
        assertTrue(has(violations(KANBAN, off + ", \"done\": {\"onBoard\": false}}}"), "states", "needs at least one state on the board"));
        assertTrue(LevelResolver.resolve(KANBAN, patch(off + "}}")).ok());
    }

    @Test
    @DisplayName("D9 an END_STATE declares its outcome, DELIVERED or DISCONTINUED, and no other state has one; a team adds its own end states")
    void d9() {
        assertTrue(has(violations(SCRUM, "{\"states\": {\"wont-fix\": {\"name\": \"W\", " + END + ", \"after\": \"done\"}}}"),
                "states.wont-fix.outcome", "an END_STATE state must declare one of [DELIVERED, DISCONTINUED], was null"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"done\": {\"outcome\": \"SHIPPED\"}}}"), "states.done.outcome", "an END_STATE state must declare"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"todo\": {\"outcome\": \"DELIVERED\"}}}"), "states.todo.outcome", "only an END_STATE state has an outcome"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"done\": {\"category\": \"IN_PROGRESS\"}}}"), "states.done.outcome", "only an END_STATE state has an outcome"));
        assertTrue(has(violations(SCRUM, "{\"states\": {\"done\": {\"outcome\": \"DISCONTINUED\"}}}"), "states", "one END_STATE state with outcome DELIVERED"));
        Map<String, Object> endStates = patch("{\"states\": {\"wont-fix\": {\"name\": \"Won't Fix\", " + END + ", \"outcome\": \"DISCONTINUED\", \"after\": \"done\"},"
                + " \"duplicate\": {\"name\": \"Duplicate\", " + END + ", \"outcome\": \"DISCONTINUED\", \"after\": \"wont-fix\"}}}");
        assertTrue(LevelResolver.resolve(SCRUM, endStates).ok());
        assertEquals(List.of("backlog", "todo", "in-progress", "in-review", "done", "wont-fix", "duplicate", "no-go"), order(eff(SCRUM, endStates)));
        assertTrue(LevelResolver.resolve(SCRUM, patch("{\"states\": {\"done\": {\"category\": \"IN_PROGRESS\", \"outcome\": null}, \"released\": {\"name\": \"Released\", "
                + END + ", \"outcome\": \"DELIVERED\", \"after\": \"done\"}}}")).ok());
    }

    @Test
    @DisplayName("H7 members are a closed set: a typo is an error, not an ignored policy; description is allowed and survives resolution and round trip")
    void h7() {
        Map<String, Object> described = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"description\": \"Manual exploratory testing\","
                + " \"category\": \"IN_PROGRESS\", \"after\": \"in-review\"}}}");
        assertTrue(has(violations(SCRUM, "{\"states\": {\"todo\": {\"wiplimit\": 3}}}"), "states.todo.wiplimit", "unknown member"));
        assertTrue(has(violations(SCRUM, "{\"cadence\": {\"sprintdays\": 3}}"), "cadence.sprintdays", "unknown member"));
        assertTrue(LevelResolver.resolve(SCRUM, described).ok());
        assertEquals("Manual exploratory testing", state(eff(SCRUM, described), "qa").get("description"));
        assertEquals(eff(SCRUM, described), TemplateJson.parse(TemplateJson.write(eff(SCRUM, described))));
    }

    /**
     * The spike runs M6 and M7 against the hierarchy's resolved scrum@1, which is VEC-45's scrum
     * plus item type parents and a sprint cadence; this is that document, built at one level.
     */
    static final Map<String, Object> SCRUM_WITH_PARENTS = TemplateJson.obj(TemplateJson.mergePatch(SCRUM, patch(
            "{\"itemTypes\": {\"story\": {\"parents\": [\"epic\"]}, \"bug\": {\"parents\": [\"epic\"]}, \"task\": {\"parents\": [\"story\", \"bug\"]}},"
                    + " \"cadence\": {\"mode\": \"sprint\", \"sprintDays\": 14}}")));

    @Test
    @DisplayName("M6 item type hierarchy: an unknown parent, a loop, or removing a type another still names as parent is rejected")
    void m6() {
        Map<String, Object> base = SCRUM_WITH_PARENTS;
        assertTrue(LevelResolver.resolve(base, NONE).ok());
        assertTrue(has(violations(base, "{\"itemTypes\": {\"story\": {\"parents\": [\"saga\"]}}}"), "itemTypes.story.parents", "references unknown item type 'saga'"));
        assertTrue(has(violations(base, "{\"itemTypes\": {\"epic\": {\"parents\": [\"task\"]}}}"), "itemTypes.epic.parents", "an item type cannot be its own ancestor"));
        assertTrue(has(violations(base, "{\"itemTypes\": {\"bug\": null}}"), "itemTypes.task.parents", "references unknown item type 'bug'"));
        assertTrue(LevelResolver.resolve(base, patch("{\"itemTypes\": {\"bug\": null, \"task\": {\"parents\": [\"story\"]}}}")).ok());
    }

    @Test
    @DisplayName("M7 cadence and gates validate: a closed set of modes, a sprint length only with sprints, no gate on an end state, approvals a positive count")
    void m7() {
        Map<String, Object> base = SCRUM_WITH_PARENTS;
        assertTrue(has(violations(base, "{\"cadence\": {\"mode\": \"waterfall\"}}"), "cadence.mode", "must be one of [sprint, flow, phase]"));
        assertTrue(has(violations(base, "{\"cadence\": {\"mode\": \"flow\"}}"), "cadence.sprintDays", "a whole number of days from 1 to 56, and only with mode sprint"));
        assertTrue(LevelResolver.resolve(base, patch("{\"cadence\": {\"mode\": \"flow\", \"sprintDays\": null}}")).ok());
        assertTrue(has(violations(base, "{\"states\": {\"done\": {\"gate\": {\"approvals\": 1}}}}"), "states.done.gate", "an END_STATE state cannot be a gate"));
        assertTrue(has(violations(base, "{\"states\": {\"in-review\": {\"gate\": {\"approvals\": 0}}}}"), "states.in-review.gate.approvals", "must be a positive integer"));
        assertTrue(has(violations(base, "{\"states\": {\"in-review\": {\"gate\": true}}}"), "states.in-review.gate", "must be an object"));
    }

    @Test
    @DisplayName("D1 (resolution) both templates end in done (DELIVERED) and no-go (DISCONTINUED, off the board, last)")
    void d1() {
        for (Map<String, Object> t : List.of(SCRUM, KANBAN)) {
            Map<String, Object> e = eff(t, NONE);
            assertEquals("END_STATE", state(e, "done").get("category"));
            assertEquals("DELIVERED", state(e, "done").get("outcome"));
            assertEquals("END_STATE", state(e, "no-go").get("category"));
            assertEquals("DISCONTINUED", state(e, "no-go").get("outcome"));
            assertEquals(false, state(e, "no-go").get("onBoard"));
            assertEquals("no-go", order(e).getLast());
        }
    }

    @Test
    @DisplayName("sprintDays runs from 1 to 56")
    void sprintDaysBounds() {
        assertTrue(LevelResolver.resolve(SCRUM_WITH_PARENTS, patch("{\"cadence\": {\"sprintDays\": 56}}")).ok());
        assertTrue(has(violations(SCRUM_WITH_PARENTS, "{\"cadence\": {\"sprintDays\": 57}}"), "cadence.sprintDays", "from 1 to 56"));
        assertTrue(has(violations(SCRUM_WITH_PARENTS, "{\"cadence\": {\"sprintDays\": 0}}"), "cadence.sprintDays", "from 1 to 56"));
    }

    @Test
    @DisplayName("a scale is one of fibonacci or linear, on a number field only")
    void fieldScale() {
        assertTrue(has(violations(SCRUM, "{\"fields\": {\"storyPoints\": {\"type\": \"text\"}}}"), "fields.storyPoints.scale", "on a number field"));
        assertTrue(has(violations(SCRUM, "{\"fields\": {\"storyPoints\": {\"scale\": \"exponential\"}}}"), "fields.storyPoints.scale", "must be one of [fibonacci, linear]"));
        assertTrue(LevelResolver.resolve(SCRUM, patch("{\"fields\": {\"storyPoints\": {\"type\": \"text\", \"scale\": null}}}")).ok());
    }

    @Test
    @DisplayName("enterFrom is an array of state keys: a string, a number or an array of numbers is refused")
    void enterFromShape() {
        for (String x : List.of("\"in-review\"", "7", "[7]")) {
            List<Violation> v = violations(SCRUM, "{\"states\": {\"done\": {\"enterFrom\": " + x + "}}}");
            assertTrue(has(v, "states.done.enterFrom", "must be an array of state keys"), () -> x + " " + v);
        }
        assertTrue(LevelResolver.resolve(SCRUM, patch("{\"states\": {\"done\": {\"enterFrom\": [\"in-review\"]}}}")).ok());
    }

    /** Scrum with In Review as a gate before Done, the gate locked as the parent level would lock it. */
    static Map<String, Object> gatedScrum(String locks) {
        return TemplateJson.obj(TemplateJson.mergePatch(SCRUM, patch("{\"states\": {\"in-review\": {\"gate\": {\"approvals\": 1}},"
                + " \"done\": {\"enterFrom\": [\"in-review\"]}}, \"locks\": " + locks + "}")));
    }

    @Test
    @DisplayName("gatedDelivery: every way to a DELIVERED end passes a locked gate; an unlocked gate or a missing one does not count; the flag is a boolean")
    void gatedDelivery() {
        Map<String, Object> on = patch("{\"settings\": {\"gatedDelivery\": true}}");
        assertTrue(LevelResolver.resolve(gatedScrum("[\"states.in-review.gate\"]"), on).ok());
        assertTrue(LevelResolver.resolve(gatedScrum("[\"states.in-review\"]"), on).ok());
        List<Violation> unlocked = LevelResolver.resolve(gatedScrum("[]"), on).violations();
        assertTrue(has(unlocked, "settings.gatedDelivery", "'backlog' reaches the DELIVERED end state 'done' without passing a locked gate"), unlocked::toString);
        assertTrue(has(violations(SCRUM, "{\"settings\": {\"gatedDelivery\": true}}"), "settings.gatedDelivery", "'todo' reaches the DELIVERED end state 'done'"));
        assertTrue(has(violations(SCRUM, "{\"settings\": {\"gatedDelivery\": \"yes\"}}"), "settings.gatedDelivery", "must be true or false"));
    }

    @Test
    @DisplayName("locks name a section, an element or one member of it, and nothing that does not exist")
    void locks() {
        Map<String, String> refused = Map.of(
                "[\"states.in-review.gate.approvals\"]", "'states.in-review.gate.approvals' is deeper than a member",
                "[\"states.in-progress.wipLimt\"]", "'states.in-progress.wipLimt' names a member states elements do not have",
                "[\"settings.colour\"]", "'settings.colour' names a member settings elements do not have",
                "[\"board\"]", "'board' does not start with a section",
                "[\"states.ghost\"]", "'states.ghost' names an element that does not exist",
                "\"stateOrder\"", "must be an array of paths");
        refused.forEach((locks, message) -> {
            List<Violation> v = LevelResolver.resolve(gatedScrum(locks), NONE).violations();
            assertTrue(has(v, "locks", message), () -> locks + " " + v);
        });
        assertTrue(LevelResolver.resolve(gatedScrum("[\"stateOrder\", \"states\", \"states.todo\", \"states.in-review.gate\", \"settings.gatedDelivery\"]"), NONE).ok());
    }

    @Test
    @DisplayName("RESOLVER_VERSION is exposed for the resolved document's resolver_version column")
    void resolverVersion() {
        assertEquals(1, TemplateResolver.RESOLVER_VERSION);
    }

    @Test
    @DisplayName("violations are typed: field and message are separate, never a string prefix; a single level carries no level yet")
    void typedViolations() {
        assertEquals(List.of(new Violation("states.in-progress.wipLimit", null, "must be a positive integer")),
                violations(SCRUM, "{\"states\": {\"in-progress\": {\"wipLimit\": 0}}}"));
        Violation v = new Violation("states.qa", "acme@1", "locked");
        assertEquals("acme@1: states.qa: locked", v.toString());
        assertEquals(v, Violation.of("states.qa", "locked").at("acme@1"));
        assertEquals(v, v.at("other@2"));
    }

    @Test
    @DisplayName("resolution is pure: neither input is modified")
    void pure() {
        Map<String, Object> template = load("vec45/scrum.v1.json");
        Map<String, Object> delta = load("vec45/instance-review.json");
        LevelResolver.resolve(template, delta);
        assertEquals(SCRUM, template);
        assertEquals(REVIEW, delta);
    }
}
