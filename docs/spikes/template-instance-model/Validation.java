// Validation of an effective configuration document (spike, throwaway).
//
// Resolver.resolve() calls validate() on the merged, order-normalised document. It checks
// meaning only; the shape of a delta is checked by the resolver before the merge.

import java.util.Collection;
import java.util.List;
import java.util.Map;

final class Validation {

    private Validation() {
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
     * silently ignored policy, and so the published JSON Schema and this validator accept the same
     * documents (case S5). {@code description} is a free-text annotation allowed everywhere: unlike
     * a YAML comment it survives the compile to canonical JSON and every round trip.
     */
    static final Map<String, List<String>> MEMBERS = Map.of(
            "document", List.of("template", "version", "name", "description", "extends", "locks",
                    "states", "stateOrder", "itemTypes", "fields", "settings", "cadence"),
            "states", List.of("name", "description", "category", "outcome", "onBoard", "after", "before",
                    "wipLimit", "enterFrom", "gate"),
            "itemTypes", List.of("name", "description", "parents"),
            "fields", List.of("name", "description", "type", "scale"),
            "settings", List.of("defaultItemType", "gatedDelivery"),
            "cadence", List.of("mode", "sprintDays"),
            "gate", List.of("approvals"));

    static void validate(Map<String, Object> doc, List<String> v) {
        members("", doc, "document", v);
        Map<String, Object> states = Json.obj(doc.get("states"));
        boolean start = false;
        boolean delivered = false;
        for (Map.Entry<String, Object> e : states.entrySet()) {
            String k = e.getKey();
            Map<String, Object> st = Json.obj(e.getValue());
            requireName("states." + k, st, v);
            members("states." + k + ".", st, "states", v);
            Object cat = st.get("category");
            if (!in(CATEGORIES, cat)) {
                v.add("states." + k + ".category: must be one of " + CATEGORIES + ", was " + Json.compact(cat));
            }
            Object outcome = st.get("outcome");
            if ("END_STATE".equals(cat) && !in(OUTCOMES, outcome)) {
                v.add("states." + k + ".outcome: an END_STATE state must declare one of " + OUTCOMES + ", was " + Json.compact(outcome));
            } else if (!"END_STATE".equals(cat) && st.containsKey("outcome")) {
                v.add("states." + k + ".outcome: only an END_STATE state has an outcome");
            }
            start |= "START_STATE".equals(cat);
            delivered |= "END_STATE".equals(cat) && "DELIVERED".equals(outcome);
            for (String anchor : List.of("after", "before")) {
                if (st.containsKey(anchor) && !(st.get(anchor) instanceof String)) {
                    v.add("states." + k + "." + anchor + ": must be a state key");
                }
            }
            if (st.containsKey("onBoard") && !(st.get("onBoard") instanceof Boolean)) {
                v.add("states." + k + ".onBoard: must be true or false");
            }
            Object wip = st.get("wipLimit");
            if (wip != null && !(wip instanceof Long n && n > 0)) {
                v.add("states." + k + ".wipLimit: must be a positive integer");
            }
            for (String from : Json.strings(st.get("enterFrom"))) {
                if (!states.containsKey(from)) {
                    v.add("states." + k + ".enterFrom: references unknown state '" + from + "'");
                }
            }
            if (st.containsKey("gate")) {
                gate("states." + k + ".gate", st, v);
            }
        }
        if (!start || !delivered) {
            v.add("states: needs at least one START_STATE state and one END_STATE state with outcome DELIVERED");
        }
        if (states.values().stream().allMatch(x -> Boolean.FALSE.equals(Json.obj(x).get("onBoard")))) {
            v.add("states: needs at least one state on the board");
        }
        Map<String, Object> types = Json.obj(doc.get("itemTypes"));
        if (types.isEmpty()) {
            v.add("itemTypes: needs at least one item type");
        }
        types.forEach((k, x) -> {
            requireName("itemTypes." + k, Json.obj(x), v);
            members("itemTypes." + k + ".", Json.obj(x), "itemTypes", v);
        });
        itemTypeHierarchy(types, v);
        Json.obj(doc.get("fields")).forEach((k, x) -> {
            Map<String, Object> f = Json.obj(x);
            requireName("fields." + k, f, v);
            members("fields." + k + ".", f, "fields", v);
            if (!in(FIELD_TYPES, f.get("type"))) {
                v.add("fields." + k + ".type: must be one of " + FIELD_TYPES);
            }
            if (f.containsKey("scale") && !("number".equals(f.get("type")) && in(SCALES, f.get("scale")))) {
                v.add("fields." + k + ".scale: must be one of " + SCALES + " on a number field");
            }
        });
        Object def = Json.obj(doc.get("settings")).get("defaultItemType");
        if (!types.containsKey(def)) {
            v.add("settings.defaultItemType: references unknown item type " + Json.compact(def));
        }
        members("settings.", Json.obj(doc.get("settings")), "settings", v);
        cadence(Json.obj(doc.get("cadence")), v);
        locks(doc, v);
        gatedDelivery(doc, v);
    }

    /** Members outside the closed set are rejected, with the path of the first unknown one. */
    private static void members(String path, Map<String, Object> el, String kind, List<String> v) {
        for (String m : el.keySet()) {
            if (!MEMBERS.get(kind).contains(m)) {
                v.add(path + m + ": unknown member; allowed: " + MEMBERS.get(kind));
            }
        }
    }

    /**
     * Item types form a hierarchy through {@code parents}: the types an item of this type may sit
     * under (epic > story > task, project > deliverable > task). No parents means a top-level type.
     * Every parent must exist and the relation must not loop back on itself.
     */
    private static void itemTypeHierarchy(Map<String, Object> types, List<String> v) {
        for (String k : new java.util.TreeSet<>(types.keySet())) {
            Object p = Json.obj(types.get(k)).get("parents");
            if (p == null) {
                continue;
            }
            if (!(p instanceof List<?> l) || !l.stream().allMatch(x -> x instanceof String)) {
                v.add("itemTypes." + k + ".parents: must be an array of item type keys");
                continue;
            }
            for (String parent : Json.strings(p)) {
                if (!types.containsKey(parent)) {
                    v.add("itemTypes." + k + ".parents: references unknown item type '" + parent + "'");
                }
            }
            java.util.Deque<String> todo = new java.util.ArrayDeque<>(Json.strings(p));
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (!todo.isEmpty()) {
                String t = todo.pop();
                if (t.equals(k)) {
                    v.add("itemTypes." + k + ".parents: an item type cannot be its own ancestor");
                    break;
                }
                if (seen.add(t) && types.get(t) instanceof Map<?, ?> m && m.get("parents") instanceof List<?>) {
                    todo.addAll(Json.strings(m.get("parents")));
                }
            }
        }
    }

    /** A gate is a decision point: leaving the state needs a recorded decision by {@code approvals} approvers. */
    private static void gate(String path, Map<String, Object> st, List<String> v) {
        if (!(st.get("gate") instanceof Map<?, ?> g)) {
            v.add(path + ": must be an object");
            return;
        }
        members(path + ".", Json.obj(g), "gate", v);
        if (!(g.get("approvals") instanceof Long n && n > 0)) {
            v.add(path + ".approvals: must be a positive integer");
        }
        if ("END_STATE".equals(st.get("category"))) {
            v.add(path + ": an END_STATE state cannot be a gate; nothing leaves it in the flow");
        }
    }

    /** Absent means continuous flow. sprintDays only makes sense, and is only allowed, with sprints. */
    private static void cadence(Map<String, Object> c, List<String> v) {
        members("cadence.", c, "cadence", v);
        Object mode = c.getOrDefault("mode", "flow");
        if (!in(CADENCES, mode)) {
            v.add("cadence.mode: must be one of " + CADENCES + ", was " + Json.compact(mode));
        }
        Object days = c.get("sprintDays");
        if (days != null && !("sprint".equals(mode) && days instanceof Long n && n > 0 && n <= 56)) {
            v.add("cadence.sprintDays: a whole number of days from 1 to 56, and only with mode sprint");
        }
    }

    /**
     * A lock is a path no descendant level may change: "stateOrder", a section ("states"), an element
     * ("states.gate-1"), or one member of an element or of an object section ("states.gate-1.gate",
     * "settings.gatedDelivery"). Nothing deeper: "states.x.gate.approvals" is refused, lock
     * "states.x.gate" instead. The member must be one the element kind allows, so a misspelt lock
     * cannot silently lock nothing; the element must exist. Hierarchy.java enforces locks.
     */
    static void locks(Map<String, Object> doc, List<String> v) {
        Object l = doc.get("locks");
        if (l == null) {
            return;
        }
        if (!(l instanceof List<?> list) || !list.stream().allMatch(x -> x instanceof String)) {
            v.add("locks: must be an array of paths");
            return;
        }
        for (String path : Json.strings(l)) {
            String[] seg = path.split("\\.", -1);
            boolean keyed = seg.length > 0 && Resolver.KEYED.contains(seg[0]);
            if (!Resolver.SECTIONS.contains(seg[0])) {
                v.add("locks: '" + path + "' does not start with a section");
            } else if (seg.length > (keyed ? 3 : seg[0].equals("stateOrder") ? 1 : 2)) {
                v.add("locks: '" + path + "' is deeper than a member; lock the member that holds it");
            } else if (keyed && seg.length > 1 && !Json.obj(doc.get(seg[0])).containsKey(seg[1])) {
                v.add("locks: '" + path + "' names an element that does not exist");
            } else if (keyed && seg.length == 3 && !MEMBERS.get(seg[0]).contains(seg[2])
                    || !keyed && seg.length == 2 && !MEMBERS.get(seg[0]).contains(seg[1])) {
                v.add("locks: '" + path + "' names a member " + seg[0] + " elements do not have");
            }
        }
    }

    /**
     * settings.gatedDelivery: every way from a START_STATE to an END_STATE with outcome DELIVERED
     * passes through a LOCKED gate: a state whose "gate" member (or the whole state) is in the
     * document's locks. A gate a level below adds, or a weak gate it puts on an unlocked state, does
     * not count, because that level could set it to anything. Hierarchy.derive arranges that the locks
     * seen here are the ancestors' (or the enabling level's own). Checked on the resolved document,
     * so it holds however a level below tries to open a path: a new delivered end state, an outcome
     * changed to DELIVERED, a state recategorised, a wider enterFrom. Edges follow enterFrom (absent:
     * from every other state).
     */
    private static void gatedDelivery(Map<String, Object> doc, List<String> v) {
        Object on = Json.obj(doc.get("settings")).get("gatedDelivery");
        if (on != null && !(on instanceof Boolean)) {
            v.add("settings.gatedDelivery: must be true or false");
        }
        if (!Boolean.TRUE.equals(on)) {
            return;
        }
        Map<String, Object> states = Json.obj(doc.get("states"));
        List<String> locked = Json.strings(doc.get("locks"));
        for (String start : new java.util.TreeSet<>(states.keySet())) {
            if (!"START_STATE".equals(Json.obj(states.get(start)).get("category"))) {
                continue;
            }
            java.util.Deque<String> todo = new java.util.ArrayDeque<>(List.of(start));
            java.util.Set<String> seen = new java.util.HashSet<>(todo);
            while (!todo.isEmpty()) {
                String from = todo.pop();
                for (String to : new java.util.TreeSet<>(states.keySet())) {
                    Object enter = Json.obj(states.get(to)).get("enterFrom");
                    if (to.equals(from) || enter != null && !Json.strings(enter).contains(from) || !seen.add(to)) {
                        continue;
                    }
                    Map<String, Object> st = Json.obj(states.get(to));
                    if ("END_STATE".equals(st.get("category")) && "DELIVERED".equals(st.get("outcome"))) {
                        v.add("settings.gatedDelivery: '" + start + "' reaches the DELIVERED end state '" + to + "' without passing a locked gate");
                    } else if (!st.containsKey("gate") || !locked.contains("states." + to + ".gate") && !locked.contains("states." + to)) {
                        todo.push(to);
                    }
                }
            }
        }
    }

    /** A missing value is simply not a member. */
    private static boolean in(Collection<String> allowed, Object value) {
        return value != null && allowed.contains(value);
    }

    private static void requireName(String path, Map<String, Object> el, List<String> v) {
        if (!(el.get("name") instanceof String n) || n.isBlank()) {
            v.add(path + ".name: required");
        }
    }
}
