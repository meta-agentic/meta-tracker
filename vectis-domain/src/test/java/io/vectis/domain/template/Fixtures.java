package io.vectis.domain.template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixtures and helpers shared by the cases ported from the VEC-45/VEC-66 spike. */
final class Fixtures {

    private Fixtures() {
    }

    static String text(String resource) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/templates/" + resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no resource templates/" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, Object> load(String resource) {
        return TemplateJson.parseObject(text(resource));
    }

    static Map<String, Object> patch(String json) {
        return TemplateJson.parseObject(json);
    }

    /** The same document with every object's members in reverse order, as a store that keeps no order may return it. */
    static Map<String, Object> reversed(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        List.copyOf(doc.keySet()).reversed().forEach(k -> out.put(k,
                doc.get(k) instanceof Map<?, ?> m ? reversed(TemplateJson.obj(m)) : doc.get(k)));
        return out;
    }

    static Map<String, Object> eff(Map<String, Object> template, Map<String, Object> delta) {
        return LevelResolver.resolve(template, delta).effective();
    }

    static List<Violation> violations(Map<String, Object> template, String delta) {
        return LevelResolver.resolve(template, patch(delta)).violations();
    }

    static Map<String, Object> states(Map<String, Object> eff) {
        return TemplateJson.obj(eff.get("states"));
    }

    static Map<String, Object> state(Map<String, Object> eff, String key) {
        return TemplateJson.obj(states(eff).get(key));
    }

    static Object name(Map<String, Object> eff, String key) {
        return state(eff, key).get("name");
    }

    static List<String> order(Map<String, Object> eff) {
        return TemplateJson.strings(eff.get("stateOrder"));
    }

    static Map<String, Object> types(Map<String, Object> eff) {
        return TemplateJson.obj(eff.get("itemTypes"));
    }

    /**
     * Whether a violation has exactly this field, no level (a single level attaches none), and a
     * message containing the fragment.
     */
    static boolean has(List<Violation> violations, String field, String messageFragment) {
        return violations.stream().anyMatch(v -> v.field().equals(field) && v.level() == null && v.message().contains(messageFragment));
    }

    /** Whether a violation has exactly this level and field, and a message containing the fragment. */
    static boolean has(List<Violation> violations, String level, String field, String messageFragment) {
        return violations.stream().anyMatch(v -> Objects.equals(v.level(), level) && v.field().equals(field) && v.message().contains(messageFragment));
    }
}
