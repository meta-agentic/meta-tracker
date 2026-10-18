package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.compact;
import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validation of a merged, order-normalised configuration document. It checks meaning only; the
 * shape of a delta is checked by the resolver before the merge.
 */
final class TemplateValidator {

    private TemplateValidator() {
    }

    /** Three categories. What reaching an END_STATE means is its outcome, never the category alone. */
    static final List<String> CATEGORIES = List.of("START_STATE", "IN_PROGRESS", "END_STATE");
    /** DISCONTINUED: work stopped without completion and with no viable path forward (a no go). */
    static final List<String> OUTCOMES = List.of("DELIVERED", "DISCONTINUED");
    static final List<String> FIELD_TYPES = List.of("number", "text", "date");
    static final List<String> SCALES = List.of("fibonacci", "linear");
    /** How work is paced: sprints (Scrum), continuous flow (Kanban), or phases separated by gates (stage-gate). */
    static final List<String> CADENCES = List.of("sprint", "flow", "phase");

    /**
     * The members each element may carry, closed so a typo ("wiplimit") is an error rather than a
     * silently ignored policy, and so {@code template.schema.json} and this validator accept the
     * same documents. {@code description} is a free-text annotation allowed everywhere.
     */
    static final Map<String, List<String>> MEMBERS = Map.of(
            "document", List.of("template", "version", "name", "description", "extends", "locks",
                    "states", "stateOrder", "itemTypes", "fields", "settings", "cadence", "deliveryGates"),
            "states", List.of("name", "description", "category", "outcome", "onBoard", "after", "before",
                    "wipLimit", "enterFrom", "gate"),
            "itemTypes", List.of("name", "description", "parents"),
            "fields", List.of("name", "description", "type", "scale"),
            "settings", List.of("defaultItemType", "gatedDelivery"),
            "cadence", List.of("mode", "sprintDays"),
            "gate", List.of("approvals"));

    static void validate(Map<String, Object> doc, List<Violation> v) {
        members("", doc, "document", v);
        Map<String, Object> states = obj(doc.get("states"));
        boolean start = false;
        boolean delivered = false;
        for (Map.Entry<String, Object> e : states.entrySet()) {
            String p = "states." + e.getKey();
            Map<String, Object> st = obj(e.getValue());
            requireName(p, st, v);
            members(p + ".", st, "states", v);
            Object cat = st.get("category");
            if (!in(CATEGORIES, cat)) {
                v.add(Violation.of(p + ".category", "must be one of " + CATEGORIES + ", was " + compact(cat)));
            }
            Object outcome = st.get("outcome");
            if ("END_STATE".equals(cat) && !in(OUTCOMES, outcome)) {
                v.add(Violation.of(p + ".outcome", "an END_STATE state must declare one of " + OUTCOMES + ", was " + compact(outcome)));
            } else if (!"END_STATE".equals(cat) && st.containsKey("outcome")) {
                v.add(Violation.of(p + ".outcome", "only an END_STATE state has an outcome"));
            }
            start |= "START_STATE".equals(cat);
            delivered |= "END_STATE".equals(cat) && "DELIVERED".equals(outcome);
            for (String anchor : List.of("after", "before")) {
                if (st.containsKey(anchor) && !(st.get(anchor) instanceof String)) {
                    v.add(Violation.of(p + "." + anchor, "must be a state key"));
                }
            }
            if (st.containsKey("onBoard") && !(st.get("onBoard") instanceof Boolean)) {
                v.add(Violation.of(p + ".onBoard", "must be true or false"));
            }
            Object wip = st.get("wipLimit");
            if (wip != null && !(wip instanceof Long n && n > 0)) {
                v.add(Violation.of(p + ".wipLimit", "must be a positive integer"));
            }
            if (st.containsKey("enterFrom") && !isKeyList(st.get("enterFrom"))) {
                v.add(Violation.of(p + ".enterFrom", "must be an array of state keys"));
            }
            for (String from : strings(st.get("enterFrom"))) {
                if (!states.containsKey(from)) {
                    v.add(Violation.of(p + ".enterFrom", "references unknown state '" + from + "'"));
                }
            }
            if (st.containsKey("gate")) {
                gate(p + ".gate", st, v);
            }
        }
        if (!start || !delivered) {
            v.add(Violation.of("states", "needs at least one START_STATE state and one END_STATE state with outcome DELIVERED"));
        }
        if (states.values().stream().allMatch(x -> Boolean.FALSE.equals(obj(x).get("onBoard")))) {
            v.add(Violation.of("states", "needs at least one state on the board"));
        }
        Map<String, Object> types = obj(doc.get("itemTypes"));
        if (types.isEmpty()) {
            v.add(Violation.of("itemTypes", "needs at least one item type"));
        }
        types.forEach((k, x) -> {
            requireName("itemTypes." + k, obj(x), v);
            members("itemTypes." + k + ".", obj(x), "itemTypes", v);
        });
        itemTypeHierarchy(types, v);
        obj(doc.get("fields")).forEach((k, x) -> {
            Map<String, Object> f = obj(x);
            requireName("fields." + k, f, v);
            members("fields." + k + ".", f, "fields", v);
            if (!in(FIELD_TYPES, f.get("type"))) {
                v.add(Violation.of("fields." + k + ".type", "must be one of " + FIELD_TYPES));
            }
            if (f.containsKey("scale") && !("number".equals(f.get("type")) && in(SCALES, f.get("scale")))) {
                v.add(Violation.of("fields." + k + ".scale", "must be one of " + SCALES + " on a number field"));
            }
        });
        Object def = obj(doc.get("settings")).get("defaultItemType");
        if (!(def instanceof String d && types.containsKey(d))) {
            v.add(Violation.of("settings.defaultItemType", "references unknown item type " + compact(def)));
        }
        members("settings.", obj(doc.get("settings")), "settings", v);
        cadence(obj(doc.get("cadence")), v);
        locks(doc, v);
        gatedDelivery(doc, v);
    }

    /** Members outside the closed set are rejected, one violation per unknown member. */
    private static void members(String path, Map<String, Object> el, String kind, List<Violation> v) {
        for (String m : el.keySet()) {
            if (!MEMBERS.get(kind).contains(m)) {
                v.add(Violation.of(path + m, "unknown member; allowed: " + MEMBERS.get(kind)));
            }
        }
    }

    /**
     * Item types form a hierarchy through {@code parents}: the types an item of this type may sit
     * under (epic > story > task). No parents means a top-level type. Every parent must exist and
     * the relation must not loop back on itself.
     */
    private static void itemTypeHierarchy(Map<String, Object> types, List<Violation> v) {
        for (String k : new TreeSet<>(types.keySet())) {
            String p = "itemTypes." + k + ".parents";
            Object parents = obj(types.get(k)).get("parents");
            if (parents == null) {
                continue;
            }
            if (!(parents instanceof List<?> l) || !l.stream().allMatch(x -> x instanceof String)) {
                v.add(Violation.of(p, "must be an array of item type keys"));
                continue;
            }
            for (String parent : strings(parents)) {
                if (!types.containsKey(parent)) {
                    v.add(Violation.of(p, "references unknown item type '" + parent + "'"));
                }
            }
            Deque<String> todo = new ArrayDeque<>(strings(parents));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String t = todo.pop();
                if (t.equals(k)) {
                    v.add(Violation.of(p, "an item type cannot be its own ancestor"));
                    break;
                }
                if (seen.add(t) && obj(types.get(t)).get("parents") instanceof List<?>) {
                    todo.addAll(strings(obj(types.get(t)).get("parents")));
                }
            }
        }
    }

    /** A gate is a decision point: leaving the state needs a recorded decision by {@code approvals} approvers. */
    private static void gate(String path, Map<String, Object> st, List<Violation> v) {
        if (!(st.get("gate") instanceof Map<?, ?> g)) {
            v.add(Violation.of(path, "must be an object"));
            return;
        }
        members(path + ".", obj(g), "gate", v);
        if (!(g.get("approvals") instanceof Long n && n > 0)) {
            v.add(Violation.of(path + ".approvals", "must be a positive integer"));
        }
        if ("END_STATE".equals(st.get("category"))) {
            v.add(Violation.of(path, "an END_STATE state cannot be a gate; nothing leaves it in the flow"));
        }
    }

    /** Absent means continuous flow. sprintDays only makes sense, and is only allowed, with sprints. */
    private static void cadence(Map<String, Object> c, List<Violation> v) {
        members("cadence.", c, "cadence", v);
        Object mode = c.getOrDefault("mode", "flow");
        if (!in(CADENCES, mode)) {
            v.add(Violation.of("cadence.mode", "must be one of " + CADENCES + ", was " + compact(mode)));
        }
        Object days = c.get("sprintDays");
        if (days != null && !("sprint".equals(mode) && days instanceof Long n && n > 0 && n <= 56)) {
            v.add(Violation.of("cadence.sprintDays", "a whole number of days from 1 to 56, and only with mode sprint"));
        }
    }

    /**
     * A lock is a path no descendant level may change: "stateOrder", a section ("states"), an
     * element ("states.gate-1"), or one member of an element or of an object section
     * ("states.gate-1.gate", "settings.gatedDelivery"). Nothing deeper. The member must be one the
     * element kind allows, so a misspelt lock cannot silently lock nothing; the element must exist.
     */
    static void locks(Map<String, Object> doc, List<Violation> v) {
        Object l = doc.get("locks");
        if (l == null) {
            return;
        }
        if (!(l instanceof List<?> list) || !list.stream().allMatch(x -> x instanceof String)) {
            v.add(Violation.of("locks", "must be an array of paths"));
            return;
        }
        for (String path : strings(l)) {
            String[] seg = path.split("\\.", -1);
            boolean keyed = LevelResolver.KEYED.contains(seg[0]);
            String problem;
            if (!LevelResolver.SECTIONS.contains(seg[0])) {
                problem = "does not start with a section";
            } else if (seg.length > (keyed ? 3 : seg[0].equals("stateOrder") ? 1 : 2)) {
                problem = "is deeper than a member; lock the member that holds it";
            } else if (keyed && seg.length > 1 && !obj(doc.get(seg[0])).containsKey(seg[1])) {
                problem = "names an element that does not exist";
            } else if (keyed && seg.length == 3 && !MEMBERS.get(seg[0]).contains(seg[2])
                    || !keyed && seg.length == 2 && !MEMBERS.get(seg[0]).contains(seg[1])) {
                problem = "names a member " + seg[0] + " elements do not have";
            } else {
                continue;
            }
            v.add(Violation.of("locks", "'" + path + "' " + problem));
        }
    }

    /**
     * settings.gatedDelivery: every way from a START_STATE to an END_STATE with outcome DELIVERED
     * passes through a locked gate, a state whose "gate" member (or the whole state) is in the
     * document's locks. Checked on the resolved document, so it holds however a level below tries
     * to open a path. Edges follow enterFrom (absent: from every other state). The hierarchy
     * arranges which locks this sees.
     */
    private static void gatedDelivery(Map<String, Object> doc, List<Violation> v) {
        Object on = obj(doc.get("settings")).get("gatedDelivery");
        if (on != null && !(on instanceof Boolean)) {
            v.add(Violation.of("settings.gatedDelivery", "must be true or false"));
        }
        if (!Boolean.TRUE.equals(on)) {
            return;
        }
        Map<String, Object> states = obj(doc.get("states"));
        List<String> locked = strings(doc.get("locks"));
        for (String start : new TreeSet<>(states.keySet())) {
            if (!"START_STATE".equals(obj(states.get(start)).get("category"))) {
                continue;
            }
            Deque<String> todo = new ArrayDeque<>(List.of(start));
            Set<String> seen = new HashSet<>(todo);
            while (!todo.isEmpty()) {
                String from = todo.pop();
                for (String to : new TreeSet<>(states.keySet())) {
                    Object enter = obj(states.get(to)).get("enterFrom");
                    if (to.equals(from) || enter != null && !strings(enter).contains(from) || !seen.add(to)) {
                        continue;
                    }
                    Map<String, Object> st = obj(states.get(to));
                    if ("END_STATE".equals(st.get("category")) && "DELIVERED".equals(st.get("outcome"))) {
                        v.add(Violation.of("settings.gatedDelivery", "'" + start + "' reaches the DELIVERED end state '" + to
                                + "' without passing a locked gate"));
                    } else if (!st.containsKey("gate") || !locked.contains("states." + to + ".gate") && !locked.contains("states." + to)) {
                        todo.push(to);
                    }
                }
            }
        }
    }

    private static boolean isKeyList(Object x) {
        return x instanceof List<?> l && l.stream().allMatch(e -> e instanceof String);
    }

    /** A missing value is simply not a member. */
    private static boolean in(Collection<String> allowed, Object value) {
        return value != null && allowed.contains(value);
    }

    private static void requireName(String path, Map<String, Object> el, List<Violation> v) {
        if (!(el.get("name") instanceof String n) || n.isBlank()) {
            v.add(Violation.of(path + ".name", "required"));
        }
    }
}
