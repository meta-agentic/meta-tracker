package io.vectis.domain.template;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The JSON Schema keywords {@code template.schema.json} uses, and nothing else: type, enum,
 * pattern, minLength, minimum, maximum, required, properties, additionalProperties,
 * propertyNames, items, oneOf and local {@code $ref}. Test-only, so the domain takes no schema
 * library; an unknown keyword fails loudly instead of being ignored.
 */
final class SchemaCheck {

    private static final List<String> KEYWORDS = List.of("$schema", "title", "description", "$defs", "type", "enum",
            "pattern", "minLength", "minimum", "maximum", "required", "properties", "additionalProperties",
            "propertyNames", "items", "oneOf", "$ref");

    private final Map<String, Object> root;

    SchemaCheck(Map<String, Object> root) {
        this.root = root;
    }

    /** Every place the value does not conform, as "path: problem"; empty when it does. */
    List<String> check(Object value) {
        List<String> out = new ArrayList<>();
        check(root, value, "$", out);
        return out;
    }

    private void check(Map<String, Object> schema, Object x, String path, List<String> out) {
        for (String k : schema.keySet()) {
            if (!KEYWORDS.contains(k)) {
                throw new IllegalStateException("unsupported schema keyword " + k);
            }
        }
        if (schema.get("$ref") instanceof String ref) {
            check(TemplateJson.obj(TemplateJson.obj(root.get("$defs")).get(ref.substring("#/$defs/".length()))), x, path, out);
        }
        if (schema.containsKey("type") && TemplateJson.strings(schema.get("type") instanceof List<?> l ? l : List.of(schema.get("type")))
                .stream().noneMatch(t -> is(t, x))) {
            out.add(path + ": not of type " + schema.get("type"));
            return;
        }
        if (schema.get("enum") instanceof List<?> e && !e.contains(x)) {
            out.add(path + ": not one of " + e);
        }
        if (schema.get("oneOf") instanceof List<?> alternatives) {
            long matching = alternatives.stream().filter(a -> {
                List<String> o = new ArrayList<>();
                check(TemplateJson.obj(a), x, path, o);
                return o.isEmpty();
            }).count();
            if (matching != 1) {
                out.add(path + ": matches " + matching + " of the oneOf alternatives");
            }
        }
        if (x instanceof String s) {
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(s).find()) {
                out.add(path + ": does not match " + p);
            }
            if (schema.get("minLength") instanceof Long n && s.length() < n) {
                out.add(path + ": shorter than " + n);
            }
        }
        if (x instanceof Long n) {
            if (schema.get("minimum") instanceof Long min && n < min) {
                out.add(path + ": below " + min);
            }
            if (schema.get("maximum") instanceof Long max && n > max) {
                out.add(path + ": above " + max);
            }
        }
        if (x instanceof List<?> l && schema.get("items") instanceof Map<?, ?> items) {
            for (int i = 0; i < l.size(); i++) {
                check(TemplateJson.obj(items), l.get(i), path + "[" + i + "]", out);
            }
        }
        if (x instanceof Map<?, ?> m) {
            object(schema, TemplateJson.obj(m), path, out);
        }
    }

    private void object(Map<String, Object> schema, Map<String, Object> m, String path, List<String> out) {
        Map<String, Object> props = TemplateJson.obj(schema.get("properties"));
        TemplateJson.strings(schema.get("required")).stream().filter(r -> !m.containsKey(r))
                .forEach(r -> out.add(path + "." + r + ": required"));
        m.forEach((k, v) -> {
            if (schema.get("propertyNames") instanceof Map<?, ?> names) {
                check(TemplateJson.obj(names), k, path + "." + k + " (name)", out);
            }
            if (props.containsKey(k)) {
                check(TemplateJson.obj(props.get(k)), v, path + "." + k, out);
            } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                out.add(path + "." + k + ": not allowed");
            } else if (schema.get("additionalProperties") instanceof Map<?, ?> extra) {
                check(TemplateJson.obj(extra), v, path + "." + k, out);
            }
        });
    }

    private static boolean is(String type, Object x) {
        return switch (type) {
            case "object" -> x instanceof Map<?, ?>;
            case "array" -> x instanceof List<?>;
            case "string" -> x instanceof String;
            case "integer" -> x instanceof Long;
            case "number" -> x instanceof Long || x instanceof BigDecimal;
            case "boolean" -> x instanceof Boolean;
            case "null" -> x == null;
            default -> throw new IllegalStateException("unsupported type " + type);
        };
    }
}
