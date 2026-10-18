package io.vectis.domain.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateJsonTest {

    @Test
    void parsesTheTreeTypesTheResolverExpects() {
        Map<String, Object> doc = TemplateJson.parseObject("{\"a\": 1, \"b\": 1.5, \"c\": [true, null], \"d\": \"x\\n\\u00e9\", \"e\": null}");
        assertEquals(1L, doc.get("a"));
        assertEquals(new BigDecimal("1.5"), doc.get("b"));
        assertEquals(Arrays.asList(true, null), doc.get("c"));
        assertEquals("x\né", doc.get("d"));
        assertEquals(List.of("a", "b", "c", "d", "e"), List.copyOf(doc.keySet()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{\"a\": 1,}", "{\"a\": 1} x", "{\"a\": 1, \"a\": 2}", "[1 2]", "{\"a\": 1-2}",
            "{\"a\": 01}", "{\"a\": \"\\q\"}", "{\"a\": \"unterminated", "{\"a\": \"tab\there\"}", "nul", "{\"a\": +1}"})
    void refusesMalformedInputWithOneExceptionType(String text) {
        assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse(text));
    }

    @Test
    void refusesInputNestedDeeperThanTheLimit() {
        String deep = "[".repeat(TemplateJson.MAX_DEPTH + 2) + "]".repeat(TemplateJson.MAX_DEPTH + 2);
        assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse(deep));
    }

    @Test
    void writeRoundTripsEveryEscape() {
        Map<String, Object> doc = TemplateJson.parseObject("{\"s\": \"q\\\" b\\\\ n\\n t\\t r\\r c\\u0001\"}");
        assertEquals(doc, TemplateJson.parse(TemplateJson.write(doc)));
    }

    @Test
    void mergePatchFollowsRfc7396AndLeavesItsInputsAlone() {
        Map<String, Object> target = TemplateJson.parseObject("{\"a\": {\"b\": 1, \"c\": 2}, \"l\": [1, 2]}");
        Map<String, Object> patch = TemplateJson.parseObject("{\"a\": {\"b\": null, \"d\": 3}, \"l\": [3]}");
        assertEquals(TemplateJson.parseObject("{\"a\": {\"c\": 2, \"d\": 3}, \"l\": [3]}"), TemplateJson.mergePatch(target, patch));
        assertEquals(TemplateJson.parseObject("{\"a\": {\"b\": 1, \"c\": 2}, \"l\": [1, 2]}"), target);
    }
}
