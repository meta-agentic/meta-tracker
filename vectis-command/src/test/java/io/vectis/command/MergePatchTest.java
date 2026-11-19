// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * An edit's fields patch is RFC 7396. The rows are the RFC's own examples (Appendix A) whose
 * target and patch are objects, as an item's fields always are, one example per row.
 */
class MergePatchTest {

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            {"a":"b"}                | {"a":"c"}                  | {"a":"c"}
            {"a":"b"}                | {"b":"c"}                  | {"a":"b","b":"c"}
            {"a":"b"}                | {"a":null}                 | {}
            {"a":"b","b":"c"}        | {"a":null}                 | {"b":"c"}
            {"a":["b"]}              | {"a":"c"}                  | {"a":"c"}
            {"a":"c"}                | {"a":["b"]}                | {"a":["b"]}
            {"a":{"b":"c"}}          | {"a":{"b":"d","c":null}}   | {"a":{"b":"d"}}
            {"a":[{"b":"c"}]}        | {"a":[1]}                  | {"a":[1]}
            {"e":null}               | {"a":1}                    | {"e":null,"a":1}
            {"a":[1,2]}              | {"a":{"a":"b","c":null}}   | {"a":{"a":"b"}}
            {}                       | {"a":{"bb":{"ccc":null}}}  | {"a":{"bb":{}}}
            """)
    void appliesTheRfcExample(String target, String patch, String result) {
        assertEquals(json(result), MergePatch.apply(json(target), json(patch)));
    }

    @Test
    void aNullPatchChangesNothing() {
        Map<String, Object> target = json("{\"a\":1}");
        assertSame(target, MergePatch.apply(target, null));
    }

    @Test
    void theTargetIsNotChanged() {
        Map<String, Object> target = json("{\"a\":{\"b\":1},\"c\":2}");
        MergePatch.apply(target, json("{\"a\":{\"b\":null},\"c\":null}"));
        assertEquals(json("{\"a\":{\"b\":1},\"c\":2}"), target);
    }

    @Test
    void thePatchIsNotChanged() {
        Map<String, Object> patch = json("{\"a\":{\"b\":null}}");
        MergePatch.apply(json("{}"), patch);
        assertEquals(json("{\"a\":{\"b\":null}}"), patch);
    }

    private static Map<String, Object> json(String text) {
        return new JsonObject(text).getMap();
    }
}
