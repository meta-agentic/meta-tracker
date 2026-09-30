// Assertion helpers and fixtures shared by the cases in TemplateModelSpike.java (spike).

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

abstract class Cases {

    static Path dir;
    static int passed;
    static int failed;

    static Map<String, Object> load(String file) throws Exception {
        return Json.obj(Json.parse(Files.readString(dir.resolve(file))));
    }

    static Map<String, Object> patch(String json) {
        return Json.obj(Json.parse(json));
    }

    /** The next version of a template, derived from it by a merge patch (templates are authored the same way). */
    static Map<String, Object> next(Map<String, Object> template, String change) {
        Map<String, Object> t = Json.obj(Json.mergePatch(template, patch(change)));
        t.put("version", (Long) template.get("version") + 1);
        return t;
    }

    /** The same document with every object's members in reverse order, as a store that keeps no order may return it. */
    static Map<String, Object> reversed(Map<String, Object> doc) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        List.copyOf(doc.keySet()).reversed().forEach(k -> out.put(k,
                doc.get(k) instanceof Map<?, ?> m ? reversed(Json.obj(m)) : doc.get(k)));
        return out;
    }

    /** Upgrades {@code states} and its member-reversed copy; the result if both keep the board as {@code order} with no conflict and no pinned stateOrder, else null. */
    static Resolver.Upgrade inPlace(Map<String, Object> from, Map<String, Object> to, String states, String order) {
        Resolver.Upgrade u = Resolver.upgrade(from, to, patch("{\"states\": {" + states + "}}"), Map.of());
        Resolver.Upgrade w = Resolver.upgrade(from, to, reversed(patch("{\"states\": {" + states + "}}")), Map.of());
        boolean ok = u.conflicts().isEmpty() && w.conflicts().isEmpty() && u.delta().equals(w.delta()) && !u.delta().containsKey("stateOrder");
        return ok && order(u.effective()).equals(patch("{\"o\": " + order + "}").get("o")) && order(w.effective()).equals(order(u.effective())) ? u : null;
    }

    static Map<String, Object> eff(Map<String, Object> template, Map<String, Object> delta) {
        return Resolver.resolve(template, delta).effective();
    }

    static Map<String, Object> states(Map<String, Object> eff) {
        return Json.obj(eff.get("states"));
    }

    static Map<String, Object> state(Map<String, Object> eff, String key) {
        return Json.obj(states(eff).get(key));
    }

    static Object name(Map<String, Object> eff, String key) {
        return state(eff, key).get("name");
    }

    static List<String> order(Map<String, Object> eff) {
        return Json.strings(eff.get("stateOrder"));
    }

    static Map<String, Object> types(Map<String, Object> eff) {
        return Json.obj(eff.get("itemTypes"));
    }

    /** Null-safe equality, so a broken resolver reports FAIL lines instead of aborting the run. */
    static boolean is(Object actual, Object expected) {
        return java.util.Objects.equals(actual, expected);
    }

    static boolean has(List<String> lines, String fragment) {
        return lines.stream().anyMatch(l -> l.contains(fragment));
    }

    static void section(String title) {
        System.out.println("-- " + title);
    }

    static void check(String id, String what, boolean ok, Object detail) {
        if (ok) {
            passed++;
            System.out.printf("PASS %-4s %s%n", id, what);
        } else {
            failed++;
            System.out.printf("FAIL %-4s %s%n     got: %s%n", id, what, detail);
        }
    }
}
