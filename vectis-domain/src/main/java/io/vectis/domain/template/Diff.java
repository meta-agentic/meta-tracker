package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.compact;
import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The two views of what a configuration change means (VEC-45 §4): the override list a reviewer
 * reads, and the board_column statements the change implies.
 */
public final class Diff {

    private Diff() {
    }

    /** What a line of the override list does to its parent's value. */
    public enum Op { ADD, REMOVE, UNSET, CHANGE }

    /** One line of the reviewable diff: what the delta changed relative to its parent. */
    public record Change(Op op, String path, Object template, Object instance) {

        public Change {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(path, "path");
        }

        @Override
        public String toString() {
            return switch (op) {
                case ADD -> "add     " + path + " = " + compact(instance);
                case REMOVE -> "remove  " + path + "   (template: " + compact(template) + ")";
                case UNSET -> "unset   " + path + "   (template: " + compact(template) + ")";
                case CHANGE -> "change  " + path + ": " + compact(template) + " -> " + compact(instance);
            };
        }
    }

    /** The delta rendered against its parent, one line per overridden value, in key-path order (jsonb keeps no member order). */
    public static List<Change> overrides(Map<String, Object> parent, Map<String, Object> delta) {
        List<Change> out = new ArrayList<>();
        for (String s : LevelResolver.KEYED) {
            Map<String, Object> t = obj(parent.get(s));
            obj(delta.get(s)).forEach((k, x) -> {
                String path = s + "." + k;
                if (x == null) {
                    out.add(new Change(Op.REMOVE, path, t.get(k), null));
                } else if (!t.containsKey(k)) {
                    out.add(new Change(Op.ADD, path, null, x));
                } else {
                    fieldLevel(path, obj(t.get(k)), obj(x), out);
                }
            });
        }
        for (String s : List.of("settings", "cadence")) {
            fieldLevel(s, obj(parent.get(s)), obj(delta.get(s)), out);
        }
        if (delta.containsKey("stateOrder")) {
            out.add(new Change(Op.CHANGE, "stateOrder", parent.get("stateOrder"), delta.get("stateOrder")));
        }
        out.sort(Comparator.comparing(Change::path));
        return out;
    }

    private static void fieldLevel(String path, Map<String, Object> t, Map<String, Object> x, List<Change> out) {
        x.forEach((f, value) -> out.add(value == null
                ? new Change(Op.UNSET, path + "." + f, t.get(f), null)
                : new Change(t.containsKey(f) ? Op.CHANGE : Op.ADD, path + "." + f, t.get(f), value)));
    }

    /**
     * The board_column statements that move a board from one effective configuration to the next,
     * reconciled by state key. A rename is an UPDATE of the same row, so items (which reference
     * board_column.id) never move.
     */
    public static List<String> projection(Map<String, Object> before, Map<String, Object> after) {
        List<String> ops = new ArrayList<>();
        List<String> oldOrder = strings(before.get("stateOrder"));
        List<String> newOrder = strings(after.get("stateOrder"));
        Map<String, Object> oldStates = obj(before.get("states"));
        Map<String, Object> newStates = obj(after.get("states"));
        for (String k : oldOrder) {
            if (!newStates.containsKey(k)) {
                ops.add("DELETE " + k);
            }
        }
        for (int i = 0; i < newOrder.size(); i++) {
            String k = newOrder.get(i);
            Object name = obj(newStates.get(k)).get("name");
            if (!oldStates.containsKey(k)) {
                ops.add("INSERT " + k + " name=" + compact(name) + " ordinal=" + i + (onBoard(newStates, k) ? "" : " on_board=false"));
                continue;
            }
            Object oldName = obj(oldStates.get(k)).get("name");
            if (!Objects.equals(name, oldName)) {
                ops.add("UPDATE " + k + " name " + compact(oldName) + " -> " + compact(name));
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
    public static boolean onBoard(Map<String, Object> states, String k) {
        return !Boolean.FALSE.equals(obj(states.get(k)).get("onBoard"));
    }
}
