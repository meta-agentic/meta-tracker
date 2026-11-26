// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import io.vectis.domain.template.Violation;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The rules an item's own values follow, whatever the channel. Each check adds its violations to
 * the list it is given, so that one refusal names everything that is wrong.
 */
final class ItemRules {

    /** Violations are the workspace's: its rules refuse the item (ADR-VEC-05 D1). */
    static final String LEVEL = "workspace";

    static final int TITLE_MAX = 500;

    /**
     * Digits and lower-case letters only, the characters whose order is the same under every
     * collation the database may use, so a column sorts the same whatever its locale.
     */
    static final Pattern RANK = Pattern.compile("[0-9a-z]{1,128}");

    private ItemRules() {
    }

    static void title(String title, List<Violation> out) {
        if (title == null || title.isBlank()) {
            out.add(violation("title", "required", "an item has a title that is not blank"));
        } else if (title.codePointCount(0, title.length()) > TITLE_MAX) {
            out.add(violation("title", "tooLong", "a title has at most " + TITLE_MAX + " characters"));
        }
    }

    /** A rank, if one is given. */
    static void rank(String rank, List<Violation> out) {
        if (rank != null && !RANK.matcher(rank).matches()) {
            out.add(violation("rank", "pattern", "a rank is 1 to 128 digits and lower-case letters, which sort the same"
                    + " everywhere; got '" + rank + "'"));
        }
    }

    /** Every field named, if any are. */
    static void fieldNames(Map<String, Object> fields, List<Violation> out) {
        if (fields != null && fields.keySet().stream().anyMatch(String::isBlank)) {
            out.add(violation("fields", "a field has a name that is not blank"));
        }
    }

    static Violation violation(String field, String message) {
        return new Violation(field, LEVEL, message);
    }

    /** A violation with the code a client translates (ADR-VEC-05). */
    static Violation violation(String field, String code, String message) {
        return new Violation(field, LEVEL, message, code);
    }
}
