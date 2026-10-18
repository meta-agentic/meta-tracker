package io.vectis.domain.template;

import static io.vectis.domain.template.Fixtures.load;
import static io.vectis.domain.template.Fixtures.patch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The four built-ins shipped in {@code src/main/resources/templates}: each resolves cleanly, its
 * authored and resolved forms conform to {@code template.schema.json}, and its resolved form
 * equals the committed golden file. After a deliberate change to the resolution rules (and a
 * {@link TemplateResolver#RESOLVER_VERSION} bump), regenerate the golden files with
 * {@code mvn -pl vectis-domain test -Dvectis.golden.update=true} and review the diff.
 */
class BuiltInTemplatesTest {

    static final Map<String, List<String>> CHAINS = Map.of(
            "base", List.of("base"),
            "kanban", List.of("base", "kanban"),
            "scrum", List.of("base", "scrum"),
            "hybrid", List.of("base", "scrum", "hybrid"));

    static final SchemaCheck SCHEMA = new SchemaCheck(load("template.schema.json"));

    static Resolution resolve(String key) {
        return TemplateResolver.resolve(CHAINS.get(key).stream().map(k -> load(k + ".v1.json")).toList(), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"base", "kanban", "scrum", "hybrid"})
    @DisplayName("a built-in resolves cleanly and matches its golden file")
    void golden(String key) throws IOException {
        Resolution r = resolve(key);
        assertTrue(r.ok(), r::toString);
        String resolved = TemplateJson.write(r.effective());
        String file = "golden/" + key + ".v1.resolved.json";
        if (Boolean.getBoolean("vectis.golden.update")) {
            Path out = Path.of("src/test/resources/templates", file);
            Files.createDirectories(out.getParent());
            Files.writeString(out, resolved);
        }
        assertEquals(Fixtures.text(file), resolved, file + " is stale; see the class comment to regenerate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"base", "kanban", "scrum", "hybrid"})
    @DisplayName("a built-in, authored and resolved, conforms to template.schema.json")
    void schema(String key) {
        assertEquals(List.of(), SCHEMA.check(load(key + ".v1.json")));
        assertEquals(List.of(), SCHEMA.check(resolve(key).effective()));
    }

    @Test
    @DisplayName("the schema check has teeth: an unknown member, a bad key, a wrong type, an out-of-range value and an unknown enum are refused")
    void schemaRefuses() {
        assertFalse(SCHEMA.check(patch("{\"states\": {\"todo\": {\"wiplimit\": 3}}}")).isEmpty());
        assertFalse(SCHEMA.check(patch("{\"states\": {\"To Do\": {\"name\": \"x\"}}}")).isEmpty());
        assertFalse(SCHEMA.check(patch("{\"stateOrder\": \"todo\"}")).isEmpty());
        assertFalse(SCHEMA.check(patch("{\"cadence\": {\"sprintDays\": 57}}")).isEmpty());
        assertFalse(SCHEMA.check(patch("{\"states\": {\"done\": {\"outcome\": \"SHIPPED\"}}}")).isEmpty());
        assertFalse(SCHEMA.check(patch("{\"extends\": {\"template\": \"base\"}}")).isEmpty());
        assertEquals(List.of(), SCHEMA.check(patch("{\"states\": {\"todo\": null, \"qa\": {\"after\": \"in-review\"}}}")));
    }
}
