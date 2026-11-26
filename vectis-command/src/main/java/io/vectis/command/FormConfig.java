// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import io.vectis.domain.template.FieldValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A typed view of the form vocabulary in a workspace's effective configuration (ADR-VEC-03,
 * amendment 1): the declared fields, the item types and the states, read once from the open
 * document so that the rules in {@link FieldRules} never walk untyped maps.
 *
 * <p>It reads a configuration the resolver has already validated, so it is lenient where the
 * validator is strict: a member of the wrong shape reads as absent. That is also why the
 * validator does not use it: its job is to name exactly the members this view would drop.
 */
record FormConfig(Map<String, Field> fields, Map<String, ItemType> types, Map<String, State> states,
                  String defaultType) {

    /**
     * A declared field. {@code decl} is its declaration as the document holds it, which
     * {@link FieldValues} reads for the validator and for these rules alike.
     */
    record Field(String key, String name, Map<String, Object> decl) {

        Object defaultValue() {
            return decl.get("default");
        }

        /** The violation code if {@code value} is not empty and not a value of this field. */
        Optional<String> problem(Object value) {
            return FieldValues.isEmpty(value) ? Optional.empty() : Optional.ofNullable(FieldValues.problem(decl, value));
        }

        /** What a value of this field is, as a violation says it: "is a number". */
        String expected() {
            return FieldValues.expected(decl);
        }
    }

    /** An item type: the declared fields it has, in form order, and those it always needs. */
    record ItemType(String key, String name, List<String> fields, List<String> required) {
    }

    /** A state and the fields an item needs to enter it. */
    record State(String key, String name, List<String> requires) {
    }

    static FormConfig of(Map<String, Object> effective) {
        Map<String, Field> fields = entries(effective.get("fields"), (k, m) -> new Field(k, name(m, k), m));
        List<String> every = sortedKeys(fields);
        return new FormConfig(
                fields,
                entries(effective.get("itemTypes"), (k, m) -> new ItemType(k, name(m, k),
                        m.get("fields") != null ? strings(m.get("fields")) : every, strings(m.get("required")))),
                entries(effective.get("states"), (k, m) -> new State(k, name(m, k), strings(m.get("requires")))),
                map(effective.get("settings")).get("defaultItemType") instanceof String t ? t : null);
    }

    /** The type named {@code key}; an undeclared type has every declared field and needs none. */
    ItemType type(String key) {
        return Optional.ofNullable(key).map(types::get)
                .orElseGet(() -> new ItemType(key, key, sortedKeys(fields), List.of()));
    }

    /** The state named {@code key}; an undeclared state needs nothing. */
    State state(String key) {
        return Optional.ofNullable(key).map(states::get).orElseGet(() -> new State(key, key, List.of()));
    }

    /** The declared fields of {@code type} that have a default, in form order. */
    Stream<Field> withDefaults(String type) {
        return type(type).fields().stream()
                .map(fields::get)
                .filter(Objects::nonNull)
                .filter(f -> f.defaultValue() != null);
    }

    String fieldName(String key) {
        Field f = fields.get(key);
        return f == null ? key : f.name();
    }

    /** Each member of an object section as a record, in the document's order. */
    private static <T> Map<String, T> entries(Object section, BiFunction<String, Map<String, Object>, T> entry) {
        return Collections.unmodifiableMap(map(section).entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, e -> entry.apply(e.getKey(), map(e.getValue())), (a, b) -> a, LinkedHashMap::new)));
    }

    private static List<String> sortedKeys(Map<String, ?> section) {
        return section.keySet().stream().sorted().toList();
    }

    private static String name(Map<String, Object> member, String key) {
        return member.get("name") instanceof String n ? n : key;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> strings(Object value) {
        return value instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
