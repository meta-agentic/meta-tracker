package io.vectis.domain.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
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
    void acceptsExactlyMaxDepthNestedContainers() {
        int n = TemplateJson.MAX_DEPTH;
        assertEquals(n, depth(TemplateJson.parse("[".repeat(n) + "]".repeat(n))));
        assertEquals(n, depth(TemplateJson.parse("[".repeat(n) + "1" + "]".repeat(n))));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TemplateJson.parse("[".repeat(n + 1) + "]".repeat(n + 1)));
        assertTrue(e.getMessage().startsWith("nested deeper than " + n), e::getMessage);
        assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse("{\"a\": ".repeat(n + 1) + "1" + "}".repeat(n + 1)));
    }

    /** How many arrays are nested, following the first element. */
    private static int depth(Object v) {
        int d = 0;
        for (Object x = v; x instanceof List<?> l; x = l.isEmpty() ? null : l.getFirst()) {
            d++;
        }
        return d;
    }

    @Test
    void refusesALongNumberTokenBeforeConvertingIt() {
        String million = "1".repeat(1_000_000);
        IllegalArgumentException e = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse("{\"a\": " + million + "}")));
        assertTrue(e.getMessage().startsWith("number longer than " + TemplateJson.MAX_NUMBER_LENGTH + " characters"), e::getMessage);
        String longest = "0." + "1".repeat(TemplateJson.MAX_NUMBER_LENGTH - 2);
        assertEquals(new BigDecimal(longest), TemplateJson.parse(longest));
        assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse(longest + "1"));
    }

    @Test
    void refusesAnIntegerOutsideTheLongRangeByName() {
        assertEquals(Long.MAX_VALUE, TemplateJson.parse(String.valueOf(Long.MAX_VALUE)));
        assertEquals(Long.MIN_VALUE, TemplateJson.parse(String.valueOf(Long.MIN_VALUE)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse("99999999999999999999"));
        assertTrue(e.getMessage().startsWith("integer out of range"), e::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\\u+041\"", "\"\\u-001\"", "\"\\u00g1\"", "\"\\u12\"", "\"\\u\u0660\u0660\u0664\u0661\"", "\"\\u\uff10\uff10\uff14\uff21\""})
    void refusesAUnicodeEscapeThatIsNotFourHexDigits(String text) {
        assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"", "\"\\ud800\\ud800\"", "\"\ud800\""})
    void refusesAnUnpairedSurrogate(String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TemplateJson.parse(text));
        assertTrue(e.getMessage().startsWith("unpaired surrogate"), e::getMessage);
    }

    @Test
    void acceptsEscapedAndLiteralSurrogatePairs() {
        assertEquals("\ud83d\ude00", TemplateJson.parse("\"\\ud83d\\ude00\""));
        assertEquals("\ud83d\ude00", TemplateJson.parse("\"\ud83d\ude00\""));
        assertEquals("A", TemplateJson.parse("\"\\u0041\""));
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
