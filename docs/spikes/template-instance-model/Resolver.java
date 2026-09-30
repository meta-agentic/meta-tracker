// Template + delta resolution, the operation the whole model rests on (spike, throwaway).
//
// A template is an immutable, versioned configuration document. A workspace stores a
// reference (template key + pinned version) and a delta, which is an RFC 7396 merge patch
// over the template's configurable sections. The effective configuration is
//
//     effective = normalise(mergePatch(template, delta))   then validated
//
// Everything the prototype decides is in this file, except the validation of the effective
// document, which is in Validation.java:
//   - resolve():     read-time merge, order normalisation, validation
//   - writeDelta():  what an instance edit must pass (resolution + occupancy)
//   - upgrade():     re-pinning to a newer template version (the "evolution" operation)
//   - fallback():    the state an unrecognised imported status lands in
// The two views of a change, the reviewable override list and the board_column
// statements it implies, are in Diff.java.

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class Resolver {

    private Resolver() {
    }

    /** The only top-level members a delta may carry. Template identity and name are not overridable. */
    static final Set<String> SECTIONS = Set.of("states", "stateOrder", "itemTypes", "fields", "settings");

    /** Sections whose members are keyed elements, merged by key. */
    static final List<String> KEYED = List.of("states", "itemTypes", "fields");

    static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9-]{0,31}");

    record Resolution(Map<String, Object> effective, List<String> violations) {
        boolean ok() {
            return violations.isEmpty();
        }
    }

    record Upgrade(Map<String, Object> delta, Map<String, Object> effective, List<String> notes, List<String> conflicts) {
    }

    // ---- resolution ---------------------------------------------------

    static Resolution resolve(Map<String, Object> template, Map<String, Object> delta) {
        List<String> v = new ArrayList<>();
        // Shape first: a section is an object (stateOrder an array of keys), never null. A
        // section-level null would be a legal merge patch that silently drops the whole
        // template section while the overrides list shows nothing, so it is rejected.
        Map<String, Object> patch = new LinkedHashMap<>();
        delta.forEach((k, x) -> {
            if (!SECTIONS.contains(k)) {
                v.add("delta may not set '" + k + "'");
            } else if (k.equals("stateOrder") ? !isKeyList(x) : !(x instanceof Map<?, ?>)) {
                v.add(k + ": must be " + (k.equals("stateOrder") ? "an array of state keys" : "an object")
                        + (x == null ? "; a whole section cannot be removed" : ""));
            } else {
                patch.put(k, Json.deepCopy(x));
            }
        });
        List<String> listed = Json.strings(patch.get("stateOrder"));
        for (String s : KEYED) {
            Map<String, Object> t = Json.obj(template.get(s));
            Map<String, Object> section = Json.obj(patch.get(s));
            for (String k : List.copyOf(section.keySet())) {
                Object x = section.get(k);
                if (!KEY.matcher(k).matches()) {
                    v.add(s + "." + k + ": key must match " + KEY.pattern());
                }
                if (x == null && !t.containsKey(k)) {
                    v.add(s + "." + k + ": removes an element the template does not have");
                } else if (x != null && !(x instanceof Map<?, ?>)) {
                    v.add(s + "." + k + ": must be an object, or null to remove it");
                    section.remove(k);
                } else if (x != null && s.equals("states") && !t.containsKey(k) && !listed.contains(k)
                        && !(Json.obj(x).get("after") instanceof String) && !(Json.obj(x).get("before") instanceof String)) {
                    // Placement must not depend on member order, which jsonb does not keep.
                    v.add(s + "." + k + ": an added state must be placed with 'after', 'before' or stateOrder");
                }
            }
        }
        Map<String, Object> doc = Json.obj(Json.mergePatch(template, patch));

        Map<String, Object> states = Json.obj(doc.get("states"));
        List<String> order = normaliseOrder(Json.strings(template.get("stateOrder")),
                patch.containsKey("stateOrder") ? Json.strings(patch.get("stateOrder")) : null, states, v);
        Map<String, Object> ordered = new LinkedHashMap<>();
        order.forEach(k -> ordered.put(k, states.get(k)));
        doc.put("states", ordered);
        doc.put("stateOrder", order);

        Validation.validate(doc, v);
        return new Resolution(doc, v);
    }

    private static boolean isKeyList(Object x) {
        return x instanceof List<?> l && l.stream().allMatch(e -> e instanceof String);
    }

    /**
     * The order rule, in three steps.
     *   1. The instance's list (or the template's, when not overridden) is kept as written,
     *      minus keys that no longer exist and minus states placed by an anchor.
     *   2. A template state missing from it is placed right after its nearest predecessor
     *      in template order that is present, so a column the template gains lands where
     *      the template put it even in a reordered instance. Anything still unplaced is
     *      appended in key order, so the result never depends on member order; resolve()
     *      rejects an added state that reaches this step.
     *   3. A state carrying {@code "after": "<key>"} is placed immediately after that state,
     *      and one carrying {@code "before": "<key>"} immediately before it. This is how an
     *      instance places a column without restating (and so freezing) the template's order.
     */
    static List<String> normaliseOrder(List<String> templateOrder, List<String> instanceOrder,
                                       Map<String, Object> states, List<String> v) {
        Set<String> present = states.keySet();
        Map<String, String> after = new TreeMap<>();
        Map<String, String> before = new TreeMap<>();
        for (String k : new TreeSet<>(present)) {
            Map<String, Object> st = Json.obj(states.get(k));
            if (st.get("after") instanceof String a) {
                after.put(k, a);
                if (st.get("before") instanceof String) {
                    v.add("states." + k + ": set 'after' or 'before', not both");
                }
            } else if (st.get("before") instanceof String b) {
                before.put(k, b);
            }
        }
        requireDistinctAnchors(after, "after", v);
        requireDistinctAnchors(before, "before", v);
        Set<String> anchored = new TreeSet<>(after.keySet());
        anchored.addAll(before.keySet());
        List<String> base = instanceOrder != null ? instanceOrder : templateOrder;
        if (new LinkedHashSet<>(base).size() != base.size()) {
            v.add("stateOrder: lists a state twice");
        }
        if (instanceOrder != null) {
            instanceOrder.stream().filter(anchored::contains).forEach(k -> v.add("stateOrder: lists '" + k
                    + "', which is placed by states." + k + "." + (after.containsKey(k) ? "after" : "before")));
        }
        List<String> order = new ArrayList<>();
        for (String k : base) {
            if (present.contains(k) && !anchored.contains(k) && !order.contains(k)) {
                order.add(k);
            }
        }
        for (int i = 0; i < templateOrder.size(); i++) {
            String k = templateOrder.get(i);
            if (!present.contains(k) || anchored.contains(k) || order.contains(k)) {
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
        for (String k : new TreeSet<>(present)) {
            if (!anchored.contains(k) && !order.contains(k)) {
                order.add(k);
            }
        }
        for (boolean placed = true; placed; ) {
            placed = false;
            for (Map.Entry<String, String> e : after.entrySet()) {
                if (!order.contains(e.getKey()) && order.contains(e.getValue())) {
                    order.add(order.indexOf(e.getValue()) + 1, e.getKey());
                    placed = true;
                }
            }
            for (Map.Entry<String, String> e : before.entrySet()) {
                if (!order.contains(e.getKey()) && order.contains(e.getValue())) {
                    order.add(order.indexOf(e.getValue()), e.getKey());
                    placed = true;
                }
            }
        }
        for (String k : anchored) {
            if (!order.contains(k)) {
                String how = after.containsKey(k) ? "after" : "before";
                String a = after.containsKey(k) ? after.get(k) : before.get(k);
                v.add("states." + k + "." + how + ": " + (present.contains(a)
                        ? "placement cycle through '" + a + "'"
                        : "references unknown state '" + a + "'"));
                order.add(k);
            }
        }
        return order;
    }

    /** Two states anchored to the same side of one state would have no defined order between them. */
    private static void requireDistinctAnchors(Map<String, String> anchors, String how, List<String> v) {
        Map<String, String> seen = new TreeMap<>();
        anchors.forEach((k, a) -> {
            String other = seen.putIfAbsent(a, k);
            if (other != null) {
                v.add("states." + k + "." + how + ": states." + other + " is already placed " + how + " '" + a
                        + "'; place one relative to the other");
            }
        });
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
     *     override, so the instance's values win and the template fills the rest; the note
     *     names both. A state whose category or outcome disagrees is refused instead: they
     *     are what reaching the state means, and adopting it would silently change that;
     *   - a state anchored to a state the new version removed is re-anchored in place;
     *   - removed states are dropped from an overridden stateOrder.
     * A state the new version recategorises (another category or outcome) is always noted,
     * and refused while it holds items whose meaning would change: items in it would start
     * or stop counting as delivered without anyone moving them.
     * Anything else (additions, renames, reorders) is inherited by re-resolving. States are
     * rewritten in key order, each seeing the anchors rewritten before it, so the result
     * never depends on member order.
     */
    static Upgrade upgrade(Map<String, Object> from, Map<String, Object> to, Map<String, Object> delta,
                           Map<String, Integer> occupancy) {
        Map<String, Object> d = Json.obj(Json.deepCopy(delta));
        List<String> notes = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        Map<String, Object> was = resolve(from, delta).effective();
        Set<String> promotedStates = new TreeSet<>();
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
                    Map<String, Object> mine = Json.obj(x);
                    Map<String, Object> theirs = Json.obj(after.get(k));
                    List<String> differ = new ArrayList<>();
                    List<String> filled = new ArrayList<>();
                    for (String f : new TreeSet<>(theirs.keySet())) {
                        if (!mine.containsKey(f)) {
                            filled.add(f);
                        } else if (!Objects.equals(mine.get(f), theirs.get(f))) {
                            differ.add(f);
                        }
                    }
                    notes.add(s + "." + k + ": template now defines it too; instance values win"
                            + (differ.isEmpty() ? "" : " over " + differ)
                            + (filled.isEmpty() ? "" : ", template fills " + filled));
                    if (s.equals("states") && (differ.contains("category") || differ.contains("outcome"))) {
                        refused.add(s + "." + k + ": the instance added it as " + kind(mine) + ", the template now defines it as "
                                + kind(theirs) + "; align its category and outcome, or empty and remove the state, before upgrading");
                    }
                }
            }
            if (d.containsKey(s) && section.isEmpty()) {
                d.remove(s);
            }
        }
        Set<String> survives = Json.obj(resolve(to, d).effective().get("states")).keySet();
        List<String> listed = Json.strings(d.get("stateOrder"));
        for (String k : promotedStates) {
            if (!listed.contains(k) && anchorOf(Json.obj(Json.obj(d.get("states")).get(k))) == null) {
                keepInPlace(k, was, survives, d, notes);
            }
        }
        Map<String, Object> ds = Json.obj(d.get("states"));
        for (String k : new TreeSet<>(ds.keySet())) {
            Object x = ds.get(k);
            String a = x == null ? null : anchorOf(Json.obj(x));
            if (a != null && !survives.contains(a)) {
                Json.obj(x).remove("after");
                Json.obj(x).remove("before");
                notes.add("states." + k + ": template removed its anchor '" + a + "'");
                keepInPlace(k, was, survives, d, notes);
            }
        }
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
        recategorised(from, to, was, r.effective(), occupancy, notes, refused);
        List<String> conflicts = new ArrayList<>(refused);
        conflicts.addAll(r.violations());
        if (conflicts.isEmpty()) {
            conflicts.addAll(checkRemovals(was, r.effective(), occupancy));
        }
        return new Upgrade(d, r.effective(), notes, conflicts);
    }

    /**
     * A template change of a state's category or outcome is always noted. Where it changes
     * what the state means in this workspace (the instance did not set its own category and
     * outcome) and items are in it, the upgrade is refused: move them first, or keep the old
     * meaning by setting it in the delta.
     */
    private static void recategorised(Map<String, Object> from, Map<String, Object> to, Map<String, Object> was,
                                      Map<String, Object> now, Map<String, Integer> occupancy,
                                      List<String> notes, List<String> refused) {
        Map<String, Object> oldStates = Json.obj(from.get("states"));
        Map<String, Object> newStates = Json.obj(to.get("states"));
        for (String k : new TreeSet<>(oldStates.keySet())) {
            if (!newStates.containsKey(k) || !Json.obj(was.get("states")).containsKey(k)
                    || kind(Json.obj(oldStates.get(k))).equals(kind(Json.obj(newStates.get(k))))) {
                continue;
            }
            String before = kind(Json.obj(Json.obj(was.get("states")).get(k)));
            String after = kind(Json.obj(Json.obj(now.get("states")).get(k)));
            String change = "states." + k + ": template changes it from " + kind(Json.obj(oldStates.get(k)))
                    + " to " + kind(Json.obj(newStates.get(k)));
            int n = occupancy.getOrDefault("states." + k, 0);
            if (before.equals(after)) {
                notes.add(change + "; the instance's own category and outcome are kept");
            } else if (n > 0) {
                refused.add(change + " while " + n + " item(s) are in it; move them first, or keep " + before
                        + " by setting it in the delta");
            } else {
                notes.add(change + "; it holds no items");
            }
        }
    }

    /** A state's category, with its outcome when it has one: "END_STATE (DELIVERED)". */
    private static String kind(Map<String, Object> state) {
        return state.get("category") + (state.get("outcome") == null ? "" : " (" + state.get("outcome") + ")");
    }

    /**
     * Where an imported item whose status the import does not recognise lands: the first
     * state, in effective order, with the item's category and, for an END_STATE, its outcome.
     * Null when the workspace has no such state; the import then refuses the item.
     */
    static String fallback(Map<String, Object> effective, String category, String outcome) {
        Map<String, Object> states = Json.obj(effective.get("states"));
        for (String k : Json.strings(effective.get("stateOrder"))) {
            Map<String, Object> st = Json.obj(states.get(k));
            if (category.equals(st.get("category")) && (!"END_STATE".equals(category) || Objects.equals(outcome, st.get("outcome")))) {
                return k;
            }
        }
        return null;
    }

    /** The state a state is anchored to, by "after" or "before", or null. */
    private static String anchorOf(Map<String, Object> state) {
        return state.get("after") instanceof String a ? a : state.get("before") instanceof String b ? b : null;
    }

    /**
     * Keep an instance-owned state where it was on the board: anchor it after its nearest
     * predecessor (in the pre-upgrade order) that survives the upgrade or, when nothing
     * before it survives (it was, or is about to become, the first column), before its
     * nearest surviving successor. A neighbour that is itself placed, through the delta's
     * anchors, relative to this state is skipped: anchoring to it would close a cycle, and
     * it moves with this state anyway. Either way the delta stays sparse. Only when every
     * other surviving state is placed relative to this one does it fall back to pinning
     * stateOrder.
     */
    private static void keepInPlace(String k, Map<String, Object> was, Set<String> survives,
                                    Map<String, Object> d, List<String> notes) {
        List<String> old = Json.strings(was.get("stateOrder"));
        for (int i = old.indexOf(k) - 1; i >= 0; i--) {
            if (canAnchor(old.get(i), k, survives, d)) {
                Json.obj(Json.obj(d.get("states")).get(k)).put("after", old.get(i));
                notes.add("states." + k + ": placed after '" + old.get(i) + "' so it stays where it was");
                return;
            }
        }
        for (int i = old.indexOf(k) + 1; i < old.size(); i++) {
            if (canAnchor(old.get(i), k, survives, d)) {
                Json.obj(Json.obj(d.get("states")).get(k)).put("before", old.get(i));
                notes.add("states." + k + ": placed before '" + old.get(i) + "' so it stays where it was");
                return;
            }
        }
        Map<String, Object> states = Json.obj(d.get("states"));
        List<String> pinned = new ArrayList<>();
        for (String s : old) {
            if (survives.contains(s) && anchorOf(Json.obj(states.get(s))) == null) {
                pinned.add(s);
            }
        }
        d.put("stateOrder", pinned);
        notes.add("stateOrder: pinned so states." + k + " stays where it was");
    }

    /** Whether k may be anchored to c: c survives and its chain of anchors in the delta, as rewritten so far, does not reach k. */
    private static boolean canAnchor(String c, String k, Set<String> survives, Map<String, Object> d) {
        if (!survives.contains(c)) {
            return false;
        }
        Map<String, Object> states = Json.obj(d.get("states"));
        Set<String> seen = new HashSet<>();
        for (String s = c; s != null && seen.add(s); s = states.get(s) == null ? null : anchorOf(Json.obj(states.get(s)))) {
            if (s.equals(k)) {
                return false;
            }
        }
        return true;
    }
}
