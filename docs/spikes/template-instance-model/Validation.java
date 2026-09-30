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

    static void validate(Map<String, Object> doc, List<String> v) {
        Map<String, Object> states = Json.obj(doc.get("states"));
        boolean start = false;
        boolean delivered = false;
        for (Map.Entry<String, Object> e : states.entrySet()) {
            String k = e.getKey();
            Map<String, Object> st = Json.obj(e.getValue());
            requireName("states." + k, st, v);
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
        types.forEach((k, x) -> requireName("itemTypes." + k, Json.obj(x), v));
        Json.obj(doc.get("fields")).forEach((k, x) -> {
            Map<String, Object> f = Json.obj(x);
            requireName("fields." + k, f, v);
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
