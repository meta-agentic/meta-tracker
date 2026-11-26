// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import io.vectis.domain.template.FieldValues;
import io.vectis.domain.template.Violation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

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
 *
 * <p>Each rule answers its violations as a stream, and each write is the rules it runs, in order.
 */
final class FieldRules {

    /** Members of {@code fields} Vectis keeps on every item, never declared by a template. */
    static final Set<String> CORE = Set.of("type", "parentId");

    private static final Predicate<String> EVERY_FIELD = k -> true;

    private FieldRules() {
    }

    /** The fields a create stores: the patch, its type (the default type if none), and the declared defaults. */
    static Map<String, Object> created(Map<String, Object> effective, Map<String, Object> patch) {
        return withDefaults(FormConfig.of(effective), MergePatch.apply(Map.of(), patch));
    }

    /** A create into {@code state} with {@code patch} as its fields. */
    static List<Violation> create(Map<String, Object> effective, String state, Map<String, Object> patch) {
        FormConfig config = FormConfig.of(effective);
        Map<String, Object> asked = MergePatch.apply(Map.of(), patch);
        Object type = asked.containsKey("type") ? asked.get("type") : config.defaultType();
        if (!(type instanceof String t) || !config.types().containsKey(t)) {
            return List.of(ItemRules.violation("fields.type", "unknownType",
                    "this workspace has no item type " + quoted(type)));
        }
        Map<String, Object> after = withDefaults(config, asked);
        return concat(
                values(config, t, after, after.keySet()),
                required(config, t, after, EVERY_FIELD),
                requires(config, t, state, after, EVERY_FIELD));
    }

    /** An edit of an item in {@code state}, whose fields are {@code before}, by {@code patch}. */
    static List<Violation> edit(Map<String, Object> effective, String state, Map<String, Object> before,
                                Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return List.of();
        }
        FormConfig config = FormConfig.of(effective);
        String type = typeOf(config, before);
        Map<String, Object> after = MergePatch.apply(before, patch);
        return concat(
                typeChanged(before, patch),
                values(config, type, after, patch.keySet()),
                required(config, type, after, patch::containsKey),
                requires(config, type, state, after, patch::containsKey));
    }

    /** A move of an item whose fields are {@code before} into {@code state}, with {@code patch} set on the way. */
    static List<Violation> move(Map<String, Object> effective, String state, Map<String, Object> before,
                                Map<String, Object> patch) {
        FormConfig config = FormConfig.of(effective);
        String type = typeOf(config, before);
        Map<String, Object> after = MergePatch.apply(before, patch);
        return concat(
                typeChanged(before, patch),
                values(config, type, after, patch == null ? Set.of() : patch.keySet()),
                requires(config, type, state, after, EVERY_FIELD));
    }

    /** {@code asked}, with its type set and each declared default it leaves out. */
    private static Map<String, Object> withDefaults(FormConfig config, Map<String, Object> asked) {
        Map<String, Object> out = new LinkedHashMap<>(asked);
        String type = typeOf(config, asked);
        out.put("type", type);
        config.withDefaults(type).forEach(f -> out.putIfAbsent(f.key(), f.defaultValue()));
        return out;
    }

    /** An item's type is set when it is created. */
    private static Stream<Violation> typeChanged(Map<String, Object> before, Map<String, Object> patch) {
        return patch != null && patch.containsKey("type") && !Objects.equals(patch.get("type"), before.get("type"))
                ? Stream.of(ItemRules.violation("fields.type", "type", "an item's type is set when it is created"))
                : Stream.empty();
    }

    /** Each touched declared field belongs to the type and holds a value of its kind, in key order. */
    private static Stream<Violation> values(FormConfig config, String type, Map<String, Object> after,
                                            Set<String> touched) {
        FormConfig.ItemType itemType = config.type(type);
        return touched.stream().sorted()
                .filter(k -> !CORE.contains(k))
                .map(config.fields()::get)
                .filter(Objects::nonNull)
                .flatMap(f -> itemType.fields().contains(f.key())
                        ? f.problem(after.get(f.key())).stream()
                                .map(code -> ItemRules.violation("fields." + f.key(), code, f.name() + " " + f.expected()))
                        : Stream.of(ItemRules.violation("fields." + f.key(), "notOnType",
                                "a " + itemType.name() + " has no " + f.name())));
    }

    /** The type's required fields have values; on an edit, only those it touches. */
    private static Stream<Violation> required(FormConfig config, String type, Map<String, Object> after,
                                              Predicate<String> touched) {
        FormConfig.ItemType itemType = config.type(type);
        return itemType.required().stream()
                .filter(touched)
                .filter(k -> FieldValues.isEmpty(after.get(k)))
                .map(k -> ItemRules.violation("fields." + k, "required",
                        "a " + itemType.name() + " has a " + config.fieldName(k)));
    }

    /** What the state needs, of the fields the type has; on an edit, only those it touches. */
    private static Stream<Violation> requires(FormConfig config, String type, String state, Map<String, Object> after,
                                              Predicate<String> touched) {
        FormConfig.State target = config.state(state);
        List<String> has = config.type(type).fields();
        return target.requires().stream()
                .filter(has::contains)
                .filter(touched)
                .filter(k -> FieldValues.isEmpty(after.get(k)))
                .map(k -> ItemRules.violation("fields." + k, "requiredToEnter",
                        target.name() + " needs " + config.fieldName(k)));
    }

    private static String typeOf(FormConfig config, Map<String, Object> fields) {
        return fields.get("type") instanceof String t ? t : config.defaultType();
    }

    private static String quoted(Object value) {
        return value instanceof String s ? "'" + s + "'" : String.valueOf(value);
    }

    @SafeVarargs
    private static List<Violation> concat(Stream<Violation>... rules) {
        return Stream.of(rules).flatMap(Function.identity()).toList();
    }
}
