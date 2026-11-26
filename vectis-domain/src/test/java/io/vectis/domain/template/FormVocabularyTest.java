// SPDX-License-Identifier: Apache-2.0
package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.patch;
import static io.vectis.domain.template.Fixtures.violations;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The form vocabulary (VEC-70, ADR-VEC-03): field kinds markdown and choice with options and a
 * default, an icon on an item type or an option, an item type's fields and required fields, and the
 * fields a state requires. Each check is one row, named by the dotted path its violation carries,
 * over the shipped scrum@1 as the parent.
 */
class FormVocabularyTest {

    static final Map<String, Object> SCRUM = BuiltInTemplatesTest.resolve("scrum").effective();

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"rating\"}}}", "fields.risk.type", "must be one of"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\"}}}", "fields.risk.options", "at least one option"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": []}}}", "fields.risk.options", "at least one option"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": [\"low\"]}}}", "fields.risk.options[0]", "must be an object"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": [{\"key\": \"Low\", \"name\": \"Low\"}]}}}", "fields.risk.options[0].key", "must match"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": [{\"key\": \"low\"}]}}}", "fields.risk.options[0].name", "required"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": [{\"key\": \"low\", \"name\": \"Low\"}, {\"key\": \"low\", \"name\": \"Lower\"}]}}}", "fields.risk.options[1].key", "already an option"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": [{\"key\": \"low\", \"name\": \"Low\", \"colour\": \"red\"}]}}}", "fields.risk.options[0].colour", "unknown member"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"options\": [{\"key\": \"low\", \"name\": \"Low\", \"icon\": \"skull\"}]}}}", "fields.risk.options[0].icon", "must be one of"),
                Arguments.of("{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"text\", \"options\": [{\"key\": \"low\", \"name\": \"Low\"}]}}}", "fields.risk.options", "only a choice field"),
                Arguments.of("{\"fields\": {\"priority\": {\"default\": \"urgent\"}}}", "fields.priority.default", "is not a value of this field"),
                Arguments.of("{\"fields\": {\"storyPoints\": {\"default\": 4}}}", "fields.storyPoints.default", "is not a value of this field"),
                Arguments.of("{\"fields\": {\"due\": {\"name\": \"Due\", \"type\": \"date\", \"default\": \"2027-02-30\"}}}", "fields.due.default", "is not a value of this field"),
                Arguments.of("{\"fields\": {\"type\": {\"name\": \"Kind\", \"type\": \"text\"}}}", "fields.type", "Vectis keeps on every item"),
                Arguments.of("{\"fields\": {\"parentId\": {\"name\": \"Parent\", \"type\": \"text\"}}}", "fields.parentId", "Vectis keeps on every item"),
                Arguments.of("{\"itemTypes\": {\"story\": {\"icon\": \"rocket\"}}}", "itemTypes.story.icon", "must be one of"),
                Arguments.of("{\"itemTypes\": {\"story\": {\"fields\": \"description\"}}}", "itemTypes.story.fields", "must be an array of field keys"),
                Arguments.of("{\"itemTypes\": {\"story\": {\"fields\": [\"estimate\"]}}}", "itemTypes.story.fields", "references unknown field 'estimate'"),
                Arguments.of("{\"itemTypes\": {\"story\": {\"fields\": [\"description\", \"description\"]}}}", "itemTypes.story.fields", "lists 'description' twice"),
                Arguments.of("{\"itemTypes\": {\"story\": {\"required\": [\"estimate\"]}}}", "itemTypes.story.required", "references unknown field 'estimate'"),
                Arguments.of("{\"itemTypes\": {\"task\": {\"required\": [\"storyPoints\"]}}}", "itemTypes.task.required", "'storyPoints' is not one of this type's fields"),
                Arguments.of("{\"states\": {\"in-progress\": {\"requires\": [\"estimate\"]}}}", "states.in-progress.requires", "references unknown field 'estimate'"),
                Arguments.of("{\"states\": {\"in-progress\": {\"requires\": \"storyPoints\"}}}", "states.in-progress.requires", "must be an array of field keys"),
                Arguments.of("{\"states\": {\"in-progress\": {\"requires\": [\"storyPoints\", \"storyPoints\"]}}}", "states.in-progress.requires", "lists 'storyPoints' twice"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("refused")
    @DisplayName("each rule of the form vocabulary is refused by its own dotted path")
    void refusedByPath(String delta, String field, String message) {
        List<Violation> v = violations(SCRUM, delta);
        assertTrue(Fixtures.has(v, field, message), () -> field + " '" + message + "' not in " + v);
    }

    static Stream<Arguments> kept() {
        return Stream.of(
                Arguments.of("a choice with icons and a default", "{\"fields\": {\"risk\": {\"name\": \"Risk\", \"type\": \"choice\", \"default\": \"low\", \"options\": [{\"key\": \"low\", \"name\": \"Low\", \"icon\": \"low\"}, {\"key\": \"high\", \"name\": \"High\", \"icon\": \"high\"}]}}}"),
                Arguments.of("a markdown field on one type", "{\"fields\": {\"notes\": {\"name\": \"Notes\", \"type\": \"markdown\"}}, \"itemTypes\": {\"task\": {\"fields\": [\"description\", \"notes\"]}}}"),
                Arguments.of("a state that requires a field", "{\"states\": {\"in-progress\": {\"requires\": [\"storyPoints\"]}}}"),
                Arguments.of("a required field of a type", "{\"itemTypes\": {\"story\": {\"required\": [\"storyPoints\"]}}}"),
                Arguments.of("a type without a field list has every declared field", "{\"itemTypes\": {\"story\": {\"fields\": null, \"required\": [\"priority\"]}}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("kept")
    @DisplayName("the form vocabulary used as meant resolves")
    void kept(String what, String delta) {
        assertEquals(List.of(), violations(SCRUM, delta));
    }

    @Test
    @DisplayName("the new members are lockable, and a misspelt member is not")
    void locks() {
        Map<String, Object> doc = TemplateJson.obj(TemplateJson.mergePatch(SCRUM, patch(
                "{\"locks\": [\"states.in-progress.requires\", \"itemTypes.bug.required\", \"itemTypes.bug.fields\", \"itemTypes.bug.icon\", \"fields.priority.options\", \"fields.priority.default\"]}")));
        List<Violation> v = new java.util.ArrayList<>();
        TemplateValidator.locks(doc, v);
        assertEquals(List.of(), v);
        Map<String, Object> typo = TemplateJson.obj(TemplateJson.mergePatch(SCRUM, patch("{\"locks\": [\"states.in-progress.require\"]}")));
        TemplateValidator.locks(typo, v);
        assertEquals(1, v.size());
    }

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("{\"type\": \"number\"}", 4L, null),
                Arguments.of("{\"type\": \"number\"}", "4", "type"),
                Arguments.of("{\"type\": \"number\", \"scale\": \"fibonacci\"}", 5L, null),
                Arguments.of("{\"type\": \"number\", \"scale\": \"fibonacci\"}", 5.0, null),
                Arguments.of("{\"type\": \"number\", \"scale\": \"fibonacci\"}", 4L, "scale"),
                Arguments.of("{\"type\": \"number\", \"scale\": \"fibonacci\"}", 2.5, "scale"),
                Arguments.of("{\"type\": \"number\", \"scale\": \"linear\"}", 10L, null),
                Arguments.of("{\"type\": \"number\", \"scale\": \"linear\"}", 11L, "scale"),
                Arguments.of("{\"type\": \"number\"}", Double.NaN, "type"),
                Arguments.of("{\"type\": \"text\"}", "x", null),
                Arguments.of("{\"type\": \"text\"}", 1L, "type"),
                Arguments.of("{\"type\": \"markdown\"}", "**x**", null),
                Arguments.of("{\"type\": \"date\"}", "2027-02-28", null),
                Arguments.of("{\"type\": \"date\"}", "2027-02-30", "type"),
                Arguments.of("{\"type\": \"date\"}", "2027-2-3", "type"),
                Arguments.of("{\"type\": \"choice\", \"options\": [{\"key\": \"a\", \"name\": \"A\"}]}", "a", null),
                Arguments.of("{\"type\": \"choice\", \"options\": [{\"key\": \"a\", \"name\": \"A\"}]}", "A", "option"));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("values")
    @DisplayName("a value fits its field's kind, or the code says why not")
    void value(String decl, Object value, String code) {
        assertEquals(code, FieldValues.problem(patch(decl), value));
    }

    @Test
    @DisplayName("the schema's icon enum and the domain's icon list are the same names")
    void icons() {
        Map<String, Object> schema = Fixtures.load("template.schema.json");
        Object icons = TemplateJson.obj(TemplateJson.obj(schema.get("$defs")).get("icon")).get("enum");
        assertEquals(FieldValues.ICONS, icons);
        assertNull(FieldValues.problem(Map.of(), "anything"), "a field without a kind is not checked");
    }
}
