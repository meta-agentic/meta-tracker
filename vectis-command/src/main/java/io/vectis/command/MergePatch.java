// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON Merge Patch (RFC 7396, section 2) over an item's open fields, as plain Java values: a
 * {@link Map} is a JSON object, and anything else is a value that replaces. Neither argument is
 * changed.
 */
final class MergePatch {

    private MergePatch() {
    }

    /** {@code target} with {@code patch} applied; a null patch changes nothing. */
    static Map<String, Object> apply(Map<String, Object> target, Map<String, Object> patch) {
        if (patch == null) {
            return target;
        }
        return merge(target, patch);
    }

    private static Map<String, Object> merge(Object target, Map<?, ?> patch) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (target instanceof Map<?, ?> object) {
            object.forEach((name, value) -> out.put((String) name, value));
        }
        patch.forEach((name, value) -> {
            if (value == null) {
                out.remove(name);
            } else if (value instanceof Map<?, ?> object) {
                out.put((String) name, merge(out.get(name), object));
            } else {
                out.put((String) name, value);
            }
        });
        return out;
    }
}
