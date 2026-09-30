// The two views of what a configuration change means (spike, throwaway): the override
// list a reviewer reads, and the board_column statements the change implies.

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class Diff {

    private Diff() {
    }

    /** One line of the reviewable diff: what the instance changed relative to its template. */
    record Change(String op, String path, Object template, Object instance) {
        @Override
        public String toString() {
            return switch (op) {
                case "add" -> "add     " + path + " = " + Json.compact(instance);
                case "remove" -> "remove  " + path + "   (template: " + Json.compact(template) + ")";
                case "unset" -> "unset   " + path + "   (template: " + Json.compact(template) + ")";
                default -> "change  " + path + ": " + Json.compact(template) + " -> " + Json.compact(instance);
            };
        }
    }

    // ---- reviewability ------------------------------------------------

    /** The delta rendered against its template, one line per overridden value. */
    static List<Change> overrides(Map<String, Object> template, Map<String, Object> delta) {
        List<Change> out = new ArrayList<>();
        for (String s : Resolver.KEYED) {
            Map<String, Object> t = Json.obj(template.get(s));
            Json.obj(delta.get(s)).forEach((k, x) -> {
                String path = s + "." + k;
                if (x == null) {
                    out.add(new Change("remove", path, t.get(k), null));
                } else if (!t.containsKey(k)) {
                    out.add(new Change("add", path, null, x));
                } else {
                    fieldLevel(path, Json.obj(t.get(k)), Json.obj(x), out);
                }
            });
        }
        fieldLevel("settings", Json.obj(template.get("settings")), Json.obj(delta.get("settings")), out);
        if (delta.containsKey("stateOrder")) {
            out.add(new Change("change", "stateOrder", template.get("stateOrder"), delta.get("stateOrder")));
        }
        return out;
    }

    private static void fieldLevel(String path, Map<String, Object> t, Map<String, Object> x, List<Change> out) {
        x.forEach((f, value) -> out.add(value == null
                ? new Change("unset", path + "." + f, t.get(f), null)
                : new Change(t.containsKey(f) ? "change" : "add", path + "." + f, t.get(f), value)));
    }

    // ---- board projection ---------------------------------------------

    /**
     * The board_column statements that move a board from one effective configuration to
     * the next, reconciled by state key. A rename is an UPDATE of the same row, so items
     * (which reference board_column.id) never move.
     */
    static List<String> projection(Map<String, Object> before, Map<String, Object> after) {
        List<String> ops = new ArrayList<>();
        List<String> oldOrder = Json.strings(before.get("stateOrder"));
        List<String> newOrder = Json.strings(after.get("stateOrder"));
        Map<String, Object> oldStates = Json.obj(before.get("states"));
        Map<String, Object> newStates = Json.obj(after.get("states"));
        for (String k : oldOrder) {
            if (!newStates.containsKey(k)) {
                ops.add("DELETE " + k);
            }
        }
        for (int i = 0; i < newOrder.size(); i++) {
            String k = newOrder.get(i);
            Object name = Json.obj(newStates.get(k)).get("name");
            if (!oldStates.containsKey(k)) {
                ops.add("INSERT " + k + " name=" + Json.compact(name) + " ordinal=" + i + (onBoard(newStates, k) ? "" : " on_board=false"));
                continue;
            }
            Object oldName = Json.obj(oldStates.get(k)).get("name");
            if (!name.equals(oldName)) {
                ops.add("UPDATE " + k + " name " + Json.compact(oldName) + " -> " + Json.compact(name));
            }
            if (onBoard(oldStates, k) != onBoard(newStates, k)) {
                ops.add("UPDATE " + k + " on_board " + onBoard(oldStates, k) + " -> " + onBoard(newStates, k));
            }
            if (oldOrder.indexOf(k) != i) {
                ops.add("UPDATE " + k + " ordinal " + oldOrder.indexOf(k) + " -> " + i);
            }
        }
        return ops;
    }

    /** A state is shown on the board unless it says otherwise; its board_column row exists either way. */
    static boolean onBoard(Map<String, Object> states, String k) {
        return !Boolean.FALSE.equals(Json.obj(states.get(k)).get("onBoard"));
    }
}
