// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import io.vectis.domain.template.FieldValues;
import io.vectis.domain.template.Violation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The rules a workspace's effective configuration sets on an item's fields (ADR-VEC-03, the form
 * vocabulary): which types exist, which declared fields a type has, what each field holds, what a
 * type always needs, and what a state needs to be entered. The web client runs the same rules
 * before it sends; the conformance fixture in this module's test resources holds the two to the
 * same answers.
 *
 * <p>When each rule applies: a create is checked whole. An edit is checked only on the fields it
 * changes, so an item written before a rule existed can still be changed elsewhere, but a field it
 * changes cannot be emptied if its type or its current state needs it. A move checks what the
 * target state needs, after the move's own field patch. Undeclared members of {@code fields} are
 * the open long tail and pass; {@code type} and {@code parentId} are Vectis's own.
 */
final class FieldRules {

    /** Members of {@code fields} Vectis keeps on every item, never declared by a template. */
    static final Set<String> CORE = Set.of("type", "parentId");

    private FieldRules() {
    }

    /** The fields a create stores: the patch, its type (the default type if none), and the declared defaults. */
    static Map<String, Object> created(Map<String, Object> effective, Map<String, Object> patch) {
        Map<String, Object> out = new LinkedHashMap<>(MergePatch.apply(Map.of(), patch));
        String type = out.get("type") instanceof String t ? t : defaultType(effective);
        out.put("type", type);
        for (String k : fieldsOf(effective, type)) {
            Object d = map(map(effective.get("fields")).get(k)).get("default");
            if (d != null && !out.containsKey(k)) {
                out.put(k, d);
            }
        }
        return out;
    }

    /** A create into {@code state} with {@code patch} as its fields. */
    static List<Violation> create(Map<String, Object> effective, String state, Map<String, Object> patch) {
        List<Violation> out = new ArrayList<>();
        Map<String, Object> asked = MergePatch.apply(Map.of(), patch);
        Object type = asked.containsKey("type") ? asked.get("type") : defaultType(effective);
        if (!(type instanceof String t) || !types(effective).containsKey(t)) {
            out.add(ItemRules.violation("fields.type", "unknownType", "this workspace has no item type " + quoted(type)));
            return out;
        }
        Map<String, Object> after = created(effective, patch);
        values(effective, t, after, after.keySet(), out);
        required(effective, t, after, null, out);
        requires(effective, t, state, after, null, out);
        return out;
    }

    /** An edit of an item in {@code state}, whose fields are {@code before}, by {@code patch}. */
    static List<Violation> edit(Map<String, Object> effective, String state, Map<String, Object> before,
                                Map<String, Object> patch) {
        List<Violation> out = new ArrayList<>();
        if (patch == null || patch.isEmpty()) {
            return out;
        }
        if (patch.containsKey("type") && !java.util.Objects.equals(patch.get("type"), before.get("type"))) {
            out.add(ItemRules.violation("fields.type", "type", "an item's type is set when it is created"));
        }
        String type = before.get("type") instanceof String t ? t : defaultType(effective);
        Map<String, Object> after = MergePatch.apply(before, patch);
        values(effective, type, after, patch.keySet(), out);
        required(effective, type, after, patch.keySet(), out);
        requires(effective, type, state, after, patch.keySet(), out);
        return out;
    }

    /** A move of an item whose fields are {@code before} into {@code state}, with {@code patch} set on the way. */
    static List<Violation> move(Map<String, Object> effective, String state, Map<String, Object> before,
                                Map<String, Object> patch) {
        List<Violation> out = new ArrayList<>();
        if (patch != null && patch.containsKey("type") && !java.util.Objects.equals(patch.get("type"), before.get("type"))) {
            out.add(ItemRules.violation("fields.type", "type", "an item's type is set when it is created"));
        }
        String type = before.get("type") instanceof String t ? t : defaultType(effective);
        Map<String, Object> after = MergePatch.apply(before, patch);
        values(effective, type, after, patch == null ? Set.of() : patch.keySet(), out);
        requires(effective, type, state, after, null, out);
        return out;
    }

    /** Each touched declared field belongs to the type and holds a value of its kind. */
    private static void values(Map<String, Object> effective, String type, Map<String, Object> after,
                               Set<String> touched, List<Violation> out) {
        Map<String, Object> declared = map(effective.get("fields"));
        List<String> has = fieldsOf(effective, type);
        for (String k : new TreeSet<>(touched)) {
            if (CORE.contains(k) || !declared.containsKey(k)) {
                continue;
            }
            Map<String, Object> decl = map(declared.get(k));
            if (!has.contains(k)) {
                out.add(ItemRules.violation("fields." + k, "notOnType", "a " + typeName(effective, type) + " has no "
                        + decl.get("name")));
                continue;
            }
            Object value = after.get(k);
            String code = FieldValues.isEmpty(value) ? null : FieldValues.problem(decl, value);
            if (code != null) {
                out.add(ItemRules.violation("fields." + k, code, decl.get("name") + " " + kind(decl)));
            }
        }
    }

    /** The type's required fields have values; on an edit, only those it touches. */
    private static void required(Map<String, Object> effective, String type, Map<String, Object> after,
                                 Set<String> touched, List<Violation> out) {
        for (String k : strings(map(types(effective).get(type)).get("required"))) {
            if ((touched == null || touched.contains(k)) && FieldValues.isEmpty(after.get(k))) {
                out.add(ItemRules.violation("fields." + k, "required", "a " + typeName(effective, type) + " has a "
                        + fieldName(effective, k)));
            }
        }
    }

    /** What the state needs, of the fields the type has; on an edit, only those it touches. */
    private static void requires(Map<String, Object> effective, String type, String state, Map<String, Object> after,
                                 Set<String> touched, List<Violation> out) {
        Map<String, Object> st = map(map(effective.get("states")).get(state));
        List<String> has = fieldsOf(effective, type);
        for (String k : strings(st.get("requires"))) {
            if (has.contains(k) && (touched == null || touched.contains(k)) && FieldValues.isEmpty(after.get(k))) {
                out.add(ItemRules.violation("fields." + k, "requiredToEnter", st.get("name") + " needs "
                        + fieldName(effective, k)));
            }
        }
    }

    /** The declared fields of a type, in form order: its list, or every declared field by key. */
    static List<String> fieldsOf(Map<String, Object> effective, String type) {
        Object listed = map(types(effective).get(type)).get("fields");
        return listed != null ? strings(listed) : new ArrayList<>(new TreeSet<>(map(effective.get("fields")).keySet()));
    }

    private static String kind(Map<String, Object> decl) {
        return switch (String.valueOf(decl.get("type"))) {
            case "number" -> decl.get("scale") != null
                    ? "is one of " + FieldValues.SCALES.get((String) decl.get("scale"))
                    : "is a number";
            case "date" -> "is a date, YYYY-MM-DD";
            case "choice" -> "is one of " + FieldValues.optionKeys(decl);
            default -> "is text";
        };
    }

    private static String defaultType(Map<String, Object> effective) {
        return (String) map(effective.get("settings")).get("defaultItemType");
    }

    private static Map<String, Object> types(Map<String, Object> effective) {
        return map(effective.get("itemTypes"));
    }

    private static String typeName(Map<String, Object> effective, String type) {
        return String.valueOf(map(types(effective).get(type)).getOrDefault("name", type));
    }

    private static String fieldName(Map<String, Object> effective, String key) {
        return String.valueOf(map(map(effective.get("fields")).get(key)).getOrDefault("name", key));
    }

    private static String quoted(Object value) {
        return value instanceof String s ? "'" + s + "'" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
