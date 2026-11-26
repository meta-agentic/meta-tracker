// SPDX-License-Identifier: Apache-2.0
package io.vectis.domain.template;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a value of a declared field may be, by the field's kind (ADR-VEC-03, the form vocabulary).
 * The resolver checks a field's {@code default} with it, and the command layer every value an item
 * write sets, so the two cannot disagree on what a field holds.
 */
public final class FieldValues {

    /** The kinds of field a template declares. */
    public static final List<String> TYPES = List.of("number", "text", "date", "markdown", "choice");

    /** The point scales, each the closed list of values a number field on it takes. */
    public static final Map<String, List<Long>> SCALES = Map.of(
            "fibonacci", List.of(0L, 1L, 2L, 3L, 5L, 8L, 13L, 21L),
            "linear", List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));

    /**
     * The icons a choice option or an item type may name, drawn by every client from its own set.
     * Closed, so a misspelt icon is refused rather than drawn as nothing; the schema's enum and the
     * web client's icon set list the same names.
     */
    public static final List<String> ICONS = List.of("story", "bug", "task", "epic", "spike", "enabler", "project",
            "milestone", "deliverable", "high", "medium", "low", "flag", "warning");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private FieldValues() {
    }

    /**
     * Null if {@code value} is a value of the field {@code decl}, else the violation code that says
     * why not: {@code type} (not the field's kind of value), {@code scale} (a number off the field's
     * point scale) or {@code option} (not one of a choice's option keys). A null value is not
     * checked here: whether a field may be empty is a rule of the item type or the state.
     */
    public static String problem(Map<String, Object> decl, Object value) {
        Object kind = decl.get("type");
        return kind == null ? null : switch (String.valueOf(kind)) {
            case "number" -> number(decl.get("scale"), value);
            case "text", "markdown" -> value instanceof String ? null : "type";
            case "date" -> value instanceof String s && isDate(s) ? null : "type";
            case "choice" -> optionKeys(decl).contains(value) ? null : "option";
            default -> "type";
        };
    }

    /**
     * What a value of the field {@code decl} is, in the words a violation's message puts after the
     * field's name: "is a number", "is one of [low, medium, high]". The English fallback of the
     * codes {@link #problem} answers.
     */
    public static String expected(Map<String, Object> decl) {
        return switch (String.valueOf(decl.get("type"))) {
            case "number" -> decl.get("scale") != null
                    ? "is one of " + SCALES.get(String.valueOf(decl.get("scale")))
                    : "is a number";
            case "date" -> "is a date, YYYY-MM-DD";
            case "choice" -> "is one of " + optionKeys(decl);
            default -> "is text";
        };
    }

    /** The option keys of a choice field, in order; empty for any other field. */
    public static List<Object> optionKeys(Map<String, Object> decl) {
        if (!(decl.get("options") instanceof List<?> options)) {
            return List.of();
        }
        return options.stream().<Object>map(o -> o instanceof Map<?, ?> m ? m.get("key") : null).toList();
    }

    /** A calendar day written {@code YYYY-MM-DD}: 2027-02-30 is not one. */
    public static boolean isDate(String s) {
        if (!DATE.matcher(s).matches()) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Absent, null, or text with nothing but blanks: the field has no value. */
    public static boolean isEmpty(Object value) {
        return value == null || value instanceof String s && s.isBlank();
    }

    /** A finite number, and on {@code scale}, where there is one, one of its points. */
    private static String number(Object scale, Object value) {
        if (!(value instanceof Number n) || !finite(n)) {
            return "type";
        }
        if (scale == null) {
            return null;
        }
        BigDecimal v = new BigDecimal(n.toString());
        return SCALES.getOrDefault(String.valueOf(scale), List.of()).stream()
                .anyMatch(point -> v.compareTo(BigDecimal.valueOf(point)) == 0) ? null : "scale";
    }

    private static boolean finite(Number n) {
        return !(n instanceof Double d && (d.isNaN() || d.isInfinite()))
                && !(n instanceof Float f && (f.isNaN() || f.isInfinite()));
    }
}
