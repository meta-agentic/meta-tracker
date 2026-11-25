// THROWAWAY PROTOTYPE (VEC-67 spike): the server side of the item rules, as the
// command layer would hold them, run over the same conformance.json as the
// client's forms.ts. Run: java Conformance.java (JDK 22+, multi-file source launch).
// A FAIL line means the two evaluators disagree; the product keeps one
// fixture file that both test suites read, so they cannot drift apart.

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class Conformance {

    record V(String field, String code) {
    }

    static final List<Long> FIBONACCI = List.of(0L, 1L, 2L, 3L, 5L, 8L, 13L, 21L);
    static final List<Long> LINEAR = List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    static final List<String> CORE = List.of("type", "parentId");
    static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    public static void main(String[] args) throws Exception {
        Map<String, Map<String, Object>> templates = Map.of(
                "scrum", Json.obj(Json.parse(Files.readString(Path.of("templates/scrum.effective.json")))),
                "stage-gate", Json.obj(Json.parse(Files.readString(Path.of("templates/stage-gate.effective.json")))));
        List<?> cases = (List<?>) Json.obj(Json.parse(Files.readString(Path.of("conformance.json")))).get("cases");
        int failed = 0;
        for (Object o : cases) {
            Map<String, Object> c = Json.obj(o);
            List<V> expected = new ArrayList<>();
            for (Object pair : (List<?>) c.get("expect")) {
                expected.add(new V((String) ((List<?>) pair).get(0), (String) ((List<?>) pair).get(1)));
            }
            List<V> got = check(templates.get((String) c.get("template")), Json.obj(c.get("write")));
            boolean pass = got.equals(expected);
            failed += pass ? 0 : 1;
            System.out.println((pass ? "PASS " : "FAIL ") + c.get("name") + (pass ? "" : "  expected " + expected + " got " + got));
        }
        System.out.println(cases.size() - failed + "/" + cases.size() + " cases agree with forms.ts");
        System.exit(failed == 0 ? 0 : 1);
    }

    static List<V> check(Map<String, Object> cfg, Map<String, Object> w) {
        List<V> out = new ArrayList<>();
        String op = (String) w.get("op");
        Map<String, Object> types = Json.obj(cfg.get("itemTypes"));
        Map<String, Object> fieldDecls = Json.obj(cfg.get("fields"));
        Map<String, Object> item = Json.obj(w.get("item"));
        Map<String, Object> patch = w.get("fields") == null ? null : Json.obj(w.get("fields"));

        String title = op.equals("create") ? String.valueOf(w.getOrDefault("title", "")) : (String) w.get("title");
        if (title != null) {
            if (title.isBlank()) {
                out.add(new V("title", "required"));
            } else if (title.codePointCount(0, title.length()) > 500) {
                out.add(new V("title", "tooLong"));
            }
        }

        Map<String, Object> before = op.equals("create") ? new LinkedHashMap<>() : Json.obj(item.get("fields"));
        Map<String, Object> after = Json.obj(Json.mergePatch(Json.deepCopy(before), patch == null ? Map.of() : patch));
        String type = (String) (op.equals("create")
                ? after.getOrDefault("type", Json.obj(cfg.get("settings")).get("defaultItemType"))
                : before.get("type"));
        if (op.equals("create")) {
            if (!types.containsKey(type)) {
                out.add(new V("fields.type", "unknownType"));
                return out;
            }
            after.put("type", type);
            for (String k : fieldsOf(cfg, type)) {
                Object d = Json.obj(fieldDecls.get(k)).get("default");
                if (d != null && !after.containsKey(k)) {
                    after.put(k, d);
                }
            }
        } else if (patch != null && patch.containsKey("type")) {
            out.add(new V("fields.type", "type"));
        }

        List<String> has = fieldsOf(cfg, type);
        List<String> touched = new ArrayList<>(op.equals("create") ? after.keySet() : patch == null ? List.of() : patch.keySet());
        for (String k : touched) {
            if (CORE.contains(k) || !fieldDecls.containsKey(k)) {
                continue;
            }
            if (!has.contains(k)) {
                out.add(new V("fields." + k, "notOnType"));
                continue;
            }
            if (!empty(after.get(k))) {
                value(Json.obj(fieldDecls.get(k)), k, after.get(k), out);
            }
        }

        String state = (String) (op.equals("create") ? w.get("state") : op.equals("move") ? w.get("to") : item.get("state"));
        boolean entering = !op.equals("edit");
        for (String k : Json.strings(Json.obj(types.get(type)).get("required"))) {
            if ((entering || touched.contains(k)) && empty(after.get(k))) {
                out.add(new V("fields." + k, "required"));
            }
        }
        for (String k : Json.strings(Json.obj(Json.obj(cfg.get("states")).get(state)).get("requires"))) {
            if (has.contains(k) && empty(after.get(k)) && (entering || touched.contains(k))) {
                out.add(new V("fields." + k, "requiredToEnter"));
            }
        }
        return out;
    }

    static List<String> fieldsOf(Map<String, Object> cfg, String type) {
        Object listed = Json.obj(Json.obj(cfg.get("itemTypes")).get(type)).get("fields");
        return listed != null ? Json.strings(listed) : Json.obj(cfg.get("fields")).keySet().stream().sorted().toList();
    }

    static void value(Map<String, Object> decl, String key, Object value, List<V> out) {
        String path = "fields." + key;
        switch ((String) decl.get("type")) {
            case "number" -> {
                if (!(value instanceof Number n)) {
                    out.add(new V(path, "type"));
                } else if (decl.get("scale") != null
                        && !(decl.get("scale").equals("fibonacci") ? FIBONACCI : LINEAR).contains(n.longValue())
                        || decl.get("scale") != null && n.doubleValue() != n.longValue()) {
                    out.add(new V(path, "scale"));
                }
            }
            case "text", "markdown" -> {
                if (!(value instanceof String)) {
                    out.add(new V(path, "type"));
                }
            }
            case "date" -> {
                if (!(value instanceof String s) || !DATE.matcher(s).matches() || !isDate(s)) {
                    out.add(new V(path, "type"));
                }
            }
            case "choice" -> {
                boolean known = false;
                for (Object opt : (List<?>) decl.get("options")) {
                    known |= Json.obj(opt).get("key").equals(value);
                }
                if (!known) {
                    out.add(new V(path, "option"));
                }
            }
            default -> throw new IllegalStateException("unknown field type " + decl.get("type"));
        }
    }

    static boolean isDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static boolean empty(Object v) {
        return v == null || v instanceof String s && s.isBlank();
    }
}
