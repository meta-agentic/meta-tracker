// Template + delta resolution, the operation the whole model rests on (spike, throwaway).
//
// A template is an immutable, versioned configuration document. A workspace stores a
// reference (template id + pinned version) and a delta, which is an RFC 7396 merge patch
// over the template's configurable sections. The effective configuration is
//
//     effective = normalise(mergePatch(template, delta))   then validated
//
// Everything the prototype decides is in this file:
//   - resolve():     read-time merge, order normalisation, validation
//   - writeDelta():  what an instance edit must pass (resolution + occupancy)
//   - upgrade():     re-pinning to a newer template version (the "evolution" operation)
//   - overrides():   the reviewable diff surface derived from the delta
//   - projection():  the board_column rows a change implies (items FK into those rows)

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class Resolver {

    private Resolver() {
    }

    /** The only top-level members a delta may carry. Template identity and name are not overridable. */
    static final Set<String> SECTIONS = Set.of("states", "stateOrder", "itemTypes", "fields", "settings");

    /** Sections whose members are keyed elements, merged by key. */
    static final List<String> KEYED = List.of("states", "itemTypes", "fields");

    static final Set<String> CATEGORIES = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE");
    static final Set<String> FIELD_TYPES = Set.of("number", "text", "date");
    static final Set<String> SCALES = Set.of("fibonacci", "linear");
    static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9-]{0,31}");

    record Resolution(Map<String, Object> effective, List<String> violations) {
        boolean ok() {
            return violations.isEmpty();
        }
    }

    record Upgrade(Map<String, Object> delta, Map<String, Object> effective, List<String> notes, List<String> conflicts) {
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

    // ---- resolution ---------------------------------------------------

    static Resolution resolve(Map<String, Object> template, Map<String, Object> delta) {
        List<String> v = new ArrayList<>();
        for (String k : delta.keySet()) {
            if (!SECTIONS.contains(k)) {
                v.add("delta may not set '" + k + "'");
            }
        }
        for (String s : KEYED) {
            Map<String, Object> t = Json.obj(template.get(s));
            Json.obj(delta.get(s)).forEach((k, x) -> {
                if (!KEY.matcher(k).matches()) {
                    v.add(s + "." + k + ": key must match " + KEY.pattern());
                }
                if (x == null && !t.containsKey(k)) {
                    v.add(s + "." + k + ": removes an element the template does not have");
                }
            });
        }
        Map<String, Object> patch = new LinkedHashMap<>(delta);
        patch.keySet().retainAll(SECTIONS);
        Map<String, Object> doc = Json.obj(Json.mergePatch(template, patch));

        Map<String, Object> states = Json.obj(doc.get("states"));
        List<String> order = normaliseOrder(Json.strings(template.get("stateOrder")),
                delta.containsKey("stateOrder") ? Json.strings(delta.get("stateOrder")) : null, states, v);
        Map<String, Object> ordered = new LinkedHashMap<>();
        order.forEach(k -> ordered.put(k, states.get(k)));
        doc.put("states", ordered);
        doc.put("stateOrder", order);

        validate(doc, v);
        return new Resolution(doc, v);
    }

    /**
     * The order rule, in three steps.
     *   1. The instance's list (or the template's, when not overridden) is kept as written,
     *      minus keys that no longer exist and minus states placed by {@code after}.
     *   2. A state missing from it is placed right after its nearest predecessor in
     *      template order that is present, so a column the template gains lands where the
     *      template put it even in a reordered instance. A state the instance added but
     *      did not place goes last.
     *   3. A state carrying {@code "after": "<key>"} is placed immediately after that
     *      state. This is how an instance places a column it adds without restating (and
     *      so freezing) the template's order.
     */
    static List<String> normaliseOrder(List<String> templateOrder, List<String> instanceOrder,
                                       Map<String, Object> states, List<String> v) {
        Set<String> present = states.keySet();
        Map<String, String> anchors = new LinkedHashMap<>();
        Map<String, String> anchoredTo = new LinkedHashMap<>();
        present.stream().sorted().forEach(k -> {
            if (Json.obj(states.get(k)).get("after") instanceof String a) {
                anchors.put(k, a);
                String other = anchoredTo.putIfAbsent(a, k);
                if (other != null) {
                    v.add("states." + k + ".after: states." + other + " is already placed after '" + a
                            + "'; place one after the other");
                }
            }
        });
        List<String> base = instanceOrder != null ? instanceOrder : templateOrder;
        if (new LinkedHashSet<>(base).size() != base.size()) {
            v.add("stateOrder: lists a state twice");
        }
        if (instanceOrder != null) {
            instanceOrder.stream().filter(anchors::containsKey).forEach(k ->
                    v.add("stateOrder: lists '" + k + "', which is placed by states." + k + ".after"));
        }
        List<String> order = new ArrayList<>();
        for (String k : base) {
            if (present.contains(k) && !anchors.containsKey(k) && !order.contains(k)) {
                order.add(k);
            }
        }
        for (int i = 0; i < templateOrder.size(); i++) {
            String k = templateOrder.get(i);
            if (!present.contains(k) || anchors.containsKey(k) || order.contains(k)) {
                continue;
            }
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int p = order.indexOf(templateOrder.get(j));
                if (p >= 0) {
                    at = p + 1;
                    break;
                }
            }
            order.add(at, k);
        }
        for (String k : present) {
            if (!anchors.containsKey(k) && !order.contains(k)) {
                order.add(k);
            }
        }
        for (boolean placed = true; placed; ) {
            placed = false;
            for (Map.Entry<String, String> e : anchors.entrySet()) {
                if (!order.contains(e.getKey()) && order.contains(e.getValue())) {
                    order.add(order.indexOf(e.getValue()) + 1, e.getKey());
                    placed = true;
                }
            }
        }
        anchors.forEach((k, a) -> {
            if (!order.contains(k)) {
                v.add("states." + k + ".after: " + (present.contains(a)
                        ? "placement cycle through '" + a + "'"
                        : "references unknown state '" + a + "'"));
                order.add(k);
            }
        });
        return order;
    }

    static void validate(Map<String, Object> doc, List<String> v) {
        Map<String, Object> states = Json.obj(doc.get("states"));
        Set<String> categories = new LinkedHashSet<>();
        states.forEach((k, x) -> {
            Map<String, Object> st = Json.obj(x);
            requireName("states." + k, st, v);
            Object cat = st.get("category");
            if (!in(CATEGORIES, cat)) {
                v.add("states." + k + ".category: must be one of " + CATEGORIES + ", was " + Json.compact(cat));
            }
            categories.add(String.valueOf(cat));
            if (st.containsKey("after") && !(st.get("after") instanceof String)) {
                v.add("states." + k + ".after: must be a state key");
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
        });
        if (!categories.contains("NOT_STARTED") || !categories.contains("DONE")) {
            v.add("states: needs at least one NOT_STARTED and one DONE state");
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

    /** Set.of rejects a null probe; a missing value is simply not a member. */
    private static boolean in(Set<String> allowed, Object value) {
        return value != null && allowed.contains(value);
    }

    private static void requireName(String path, Map<String, Object> el, List<String> v) {
        if (!(el.get("name") instanceof String n) || n.isBlank()) {
            v.add(path + ".name: required");
        }
    }

    // ---- writes -------------------------------------------------------

    /**
     * An element may leave the effective configuration only when nothing uses it.
     * {@code occupancy} counts items per "states.<key>" and "itemTypes.<key>".
     */
    static List<String> checkRemovals(Map<String, Object> before, Map<String, Object> after,
                                      Map<String, Integer> occupancy) {
        List<String> v = new ArrayList<>();
        for (String s : List.of("states", "itemTypes")) {
            for (String k : Json.obj(before.get(s)).keySet()) {
                int n = occupancy.getOrDefault(s + "." + k, 0);
                if (!Json.obj(after.get(s)).containsKey(k) && n > 0) {
                    v.add(s + "." + k + ": still used by " + n + " item(s); move or retype them first");
                }
            }
        }
        return v;
    }

    /** PUT of a new delta: must resolve cleanly, reference only known states, and orphan no item. */
    static Resolution writeDelta(Map<String, Object> template, Map<String, Object> oldDelta,
                                 Map<String, Object> newDelta, Map<String, Integer> occupancy) {
        Resolution r = resolve(template, newDelta);
        List<String> v = new ArrayList<>(r.violations());
        for (String k : Json.strings(newDelta.get("stateOrder"))) {
            if (!Json.obj(r.effective().get("states")).containsKey(k)) {
                v.add("stateOrder: references unknown state '" + k + "'");
            }
        }
        if (v.isEmpty()) {
            v.addAll(checkRemovals(resolve(template, oldDelta).effective(), r.effective(), occupancy));
        }
        return new Resolution(r.effective(), v);
    }

    // ---- evolution ----------------------------------------------------

    /**
     * Re-pin an instance from {@code from} to {@code to}. The delta is rewritten only where
     * the template change would otherwise change its meaning:
     *   - an override of an element the new version removed is promoted to a full,
     *     instance-owned element (the old template's values, with the override applied)
     *     and kept in place (see keepInPlace);
     *   - a tombstone for an element the new version also removed is dropped as redundant;
     *   - an instance-added key the new version now also defines is adopted: kept as an
     *     override, so the instance's values win and the template fills the rest;
     *   - a state placed "after" a state the new version removed is re-anchored in place;
     *   - removed states are dropped from an overridden stateOrder.
     * Anything else (additions, renames, reorders) is inherited by re-resolving.
     */
    static Upgrade upgrade(Map<String, Object> from, Map<String, Object> to, Map<String, Object> delta,
                           Map<String, Integer> occupancy) {
        Map<String, Object> d = Json.obj(Json.deepCopy(delta));
        List<String> notes = new ArrayList<>();
        Map<String, Object> was = resolve(from, delta).effective();
        List<String> promotedStates = new ArrayList<>();
        for (String s : KEYED) {
            Map<String, Object> before = Json.obj(from.get(s));
            Map<String, Object> after = Json.obj(to.get(s));
            Map<String, Object> section = Json.obj(d.get(s));
            for (String k : List.copyOf(section.keySet())) {
                Object x = section.get(k);
                if (x == null && !after.containsKey(k)) {
                    section.remove(k);
                    notes.add(s + "." + k + ": template removed it as well; tombstone dropped");
                } else if (x != null && before.containsKey(k) && !after.containsKey(k)) {
                    section.put(k, Json.mergePatch(before.get(k), x));
                    notes.add(s + "." + k + ": template removed it; the instance customised it, so it keeps it as its own");
                    if (s.equals("states")) {
                        promotedStates.add(k);
                    }
                } else if (x != null && !before.containsKey(k) && after.containsKey(k)) {
                    notes.add(s + "." + k + ": template now defines it too; instance values win, template fills the rest");
                }
            }
            if (d.containsKey(s) && section.isEmpty()) {
                d.remove(s);
            }
        }
        Set<String> survives = Json.obj(resolve(to, d).effective().get("states")).keySet();
        List<String> listed = Json.strings(d.get("stateOrder"));
        for (String k : promotedStates) {
            if (!listed.contains(k) && !(Json.obj(Json.obj(d.get("states")).get(k)).get("after") instanceof String)) {
                keepInPlace(k, was, survives, d, notes);
            }
        }
        Json.obj(d.get("states")).forEach((k, x) -> {
            if (x != null && Json.obj(x).get("after") instanceof String a && !survives.contains(a)) {
                Json.obj(x).remove("after");
                notes.add("states." + k + ".after: template removed '" + a + "'");
                keepInPlace(k, was, survives, d, notes);
            }
        });
        if (d.containsKey("stateOrder")) {
            List<String> kept = Json.strings(d.get("stateOrder"));
            kept.retainAll(survives);
            d.put("stateOrder", kept);
            List<String> oldOrder = Json.strings(from.get("stateOrder"));
            List<String> newOrder = Json.strings(to.get("stateOrder"));
            oldOrder.retainAll(newOrder);
            newOrder.retainAll(oldOrder);
            if (!oldOrder.equals(newOrder)) {
                notes.add("stateOrder: template reordered its states; the instance keeps its own order");
            }
        }
        Resolution r = resolve(to, d);
        List<String> conflicts = new ArrayList<>(r.violations());
        if (conflicts.isEmpty()) {
            conflicts.addAll(checkRemovals(was, r.effective(), occupancy));
        }
        return new Upgrade(d, r.effective(), notes, conflicts);
    }

    /**
     * Keep an instance-owned state where it was on the board: anchor it after its nearest
     * predecessor (in the pre-upgrade order) that survives the upgrade. When nothing before
     * it survives (it was, or is about to become, the first column), pin stateOrder instead.
     */
    private static void keepInPlace(String k, Map<String, Object> was, Set<String> survives,
                                    Map<String, Object> d, List<String> notes) {
        List<String> old = Json.strings(was.get("stateOrder"));
        for (int i = old.indexOf(k) - 1; i >= 0; i--) {
            if (survives.contains(old.get(i))) {
                Json.obj(Json.obj(d.get("states")).get(k)).put("after", old.get(i));
                notes.add("states." + k + ": placed after '" + old.get(i) + "' so it stays where it was");
                return;
            }
        }
        Map<String, Object> states = Json.obj(d.get("states"));
        List<String> pinned = new ArrayList<>();
        for (String s : old) {
            if (survives.contains(s) && !(Json.obj(states.get(s)).get("after") instanceof String)) {
                pinned.add(s);
            }
        }
        d.put("stateOrder", pinned);
        notes.add("stateOrder: pinned so states." + k + " stays first");
    }

    // ---- reviewability ------------------------------------------------

    /** The delta rendered against its template, one line per overridden value. */
    static List<Change> overrides(Map<String, Object> template, Map<String, Object> delta) {
        List<Change> out = new ArrayList<>();
        for (String s : KEYED) {
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
                ops.add("INSERT " + k + " name=" + Json.compact(name) + " ordinal=" + i);
                continue;
            }
            Object oldName = Json.obj(oldStates.get(k)).get("name");
            if (!name.equals(oldName)) {
                ops.add("UPDATE " + k + " name " + Json.compact(oldName) + " -> " + Json.compact(name));
            }
            if (oldOrder.indexOf(k) != i) {
                ops.add("UPDATE " + k + " ordinal " + oldOrder.indexOf(k) + " -> " + i);
            }
        }
        return ops;
    }
}
