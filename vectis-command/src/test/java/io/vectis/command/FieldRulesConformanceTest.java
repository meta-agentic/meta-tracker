// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vectis.domain.template.TemplateJson;
import io.vectis.domain.template.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The command layer's side of {@code conformance/item-rules.json}: each case is a write against a
 * shipped built-in's effective configuration, and the violations it gets, as {@code [field, code]}
 * in order. The web client runs the same file (web/src/forms/rules.test.ts), so the rules it checks
 * before sending are the rules the server applies.
 */
class FieldRulesConformanceTest {

    /** The golden resolutions of the built-ins, which vectis-domain's tests hold equal to what they resolve to. */
    static final Path GOLDEN = Path.of("../vectis-domain/src/test/resources/templates/golden");

    static Map<String, Object> fixture() {
        try (InputStream in = FieldRulesConformanceTest.class.getResourceAsStream("/conformance/item-rules.json")) {
            return TemplateJson.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Object> effective(String template, Object delta) {
        try {
            Map<String, Object> eff = TemplateJson.parseObject(Files.readString(GOLDEN.resolve(template + ".v1.resolved.json")));
            return delta == null ? eff : MergePatch.apply(eff, obj(delta));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }

    static Stream<Arguments> cases() {
        return ((List<?>) fixture().get("cases")).stream().map(c -> {
            Map<String, Object> m = obj(c);
            return Arguments.of(m.get("name"), m);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theCommandLayerGivesTheFixturesViolations(String name, Map<String, Object> c) {
        Map<String, Object> cfg = effective((String) c.get("template"), c.get("delta"));
        Map<String, Object> w = obj(c.get("write"));
        Map<String, Object> item = obj(w.get("item"));
        Map<String, Object> fields = w.get("fields") == null ? null : obj(w.get("fields"));
        Map<String, Object> before = obj(item.get("fields"));
        List<Violation> got = new ArrayList<>();
        switch ((String) w.get("op")) {
            case "create" -> {
                ItemRules.title((String) w.get("title"), got);
                got.addAll(FieldRules.create(cfg, (String) w.get("state"), fields));
            }
            case "edit" -> {
                if (w.get("title") != null) {
                    ItemRules.title((String) w.get("title"), got);
                }
                got.addAll(FieldRules.edit(cfg, (String) item.get("state"), before, fields));
            }
            case "move" -> got.addAll(FieldRules.move(cfg, (String) w.get("to"), before, fields));
            default -> throw new IllegalArgumentException("op " + w.get("op"));
        }
        assertEquals(c.get("expect"), got.stream().map(v -> List.of(v.field(), v.code())).toList());
    }

    @Test
    void everyCodeTheFixtureUsesIsOneAClientTranslates() {
        List<String> codes = List.of("required", "requiredToEnter", "tooLong", "pattern", "type", "scale", "option",
                "unknownType", "notOnType", "parent", "taken");
        for (Object c : (List<?>) fixture().get("cases")) {
            for (Object pair : (List<?>) obj(c).get("expect")) {
                assertTrue(codes.contains(((List<?>) pair).get(1)), pair::toString);
            }
        }
    }
}
