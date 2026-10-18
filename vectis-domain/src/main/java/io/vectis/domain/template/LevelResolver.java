package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.deepCopy;
import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One level of resolution: a parent document plus a delta, an RFC 7396 merge patch over the
 * parent's configurable sections (ADR-VEC-03).
 *
 * <pre>effective = validate(normaliseOrder(mergePatch(parent, delta)))</pre>
 *
 * Every level of a template chain, and the workspace below it, resolves this way.
 */
final class LevelResolver {

    private LevelResolver() {
    }

    /** The only top-level members a delta may carry. Template identity and name are not overridable. */
    static final Set<String> SECTIONS = Set.of("states", "stateOrder", "itemTypes", "fields", "settings", "cadence");

    /** Sections whose members are keyed elements, merged by key. */
    static final List<String> KEYED = List.of("states", "itemTypes", "fields");

    static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9-]{0,31}");

    static Resolution resolve(Map<String, Object> parent, Map<String, Object> delta) {
        List<Violation> v = new ArrayList<>();
        // Shape first: a section is an object (stateOrder an array of keys), never null. A
        // section-level null would be a legal merge patch that silently drops the whole parent
        // section while the override list shows nothing, so it is rejected.
        Map<String, Object> patch = new LinkedHashMap<>();
        delta.forEach((k, x) -> {
            boolean order = k.equals("stateOrder");
            if (!SECTIONS.contains(k)) {
                v.add(Violation.of(k, "delta may not set '" + k + "'"));
            } else if (order ? !isKeyList(x) : !(x instanceof Map<?, ?>)) {
                v.add(Violation.of(k, "must be " + (order ? "an array of state keys" : "an object")
                        + (x == null ? "; a whole section cannot be removed" : "")));
            } else {
                patch.put(k, deepCopy(x));
            }
        });
        List<String> listed = strings(patch.get("stateOrder"));
        for (String s : KEYED) {
            Map<String, Object> t = obj(parent.get(s));
            Map<String, Object> section = obj(patch.get(s));
            for (String k : List.copyOf(section.keySet())) {
                Object x = section.get(k);
                String p = s + "." + k;
                if (!KEY.matcher(k).matches()) {
                    v.add(Violation.of(p, "key must match " + KEY.pattern()));
                }
                if (x == null && !t.containsKey(k)) {
                    v.add(Violation.of(p, "removes an element the template does not have"));
                } else if (x != null && !(x instanceof Map<?, ?>)) {
                    v.add(Violation.of(p, "must be an object, or null to remove it"));
                    section.remove(k);
                } else if (x != null && s.equals("states") && !t.containsKey(k) && !listed.contains(k)
                        && !(obj(x).get("after") instanceof String) && !(obj(x).get("before") instanceof String)) {
                    // Placement must not depend on member order, which jsonb does not keep.
                    v.add(Violation.of(p, "an added state must be placed with 'after', 'before' or stateOrder"));
                }
            }
        }
        Map<String, Object> doc = obj(TemplateJson.mergePatch(parent, patch));

        Map<String, Object> states = obj(doc.get("states"));
        List<String> order = normaliseOrder(strings(parent.get("stateOrder")),
                patch.containsKey("stateOrder") ? strings(patch.get("stateOrder")) : null, states, v);
        Map<String, Object> ordered = new LinkedHashMap<>();
        order.forEach(k -> ordered.put(k, states.get(k)));
        doc.put("states", ordered);
        doc.put("stateOrder", order);

        TemplateValidator.validate(doc, v);
        return new Resolution(doc, v);
    }

    private static boolean isKeyList(Object x) {
        return x instanceof List<?> l && l.stream().allMatch(e -> e instanceof String);
    }

    /**
     * The order rule, in three steps.
     * <ol>
     * <li>The delta's list (or the parent's, when not overridden) is kept as written, minus keys
     *     that no longer exist and minus states placed by an anchor.</li>
     * <li>A parent state missing from it is placed right after its nearest predecessor in parent
     *     order that is present, so a column the parent gains lands where the parent put it even
     *     in a reordered child. Anything still unplaced is appended in key order, so the result
     *     never depends on member order; resolve() rejects an added state that reaches this step.</li>
     * <li>A state carrying {@code "after": "<key>"} is placed immediately after that state, and one
     *     carrying {@code "before": "<key>"} immediately before it.</li>
     * </ol>
     */
    static List<String> normaliseOrder(List<String> parentOrder, List<String> deltaOrder,
                                       Map<String, Object> states, List<Violation> v) {
        Set<String> present = states.keySet();
        Map<String, String> after = new TreeMap<>();
        Map<String, String> before = new TreeMap<>();
        for (String k : new TreeSet<>(present)) {
            Map<String, Object> st = obj(states.get(k));
            if (st.get("after") instanceof String a) {
                after.put(k, a);
                if (st.get("before") instanceof String) {
                    v.add(Violation.of("states." + k, "set 'after' or 'before', not both"));
                }
            } else if (st.get("before") instanceof String b) {
                before.put(k, b);
            }
        }
        requireDistinctAnchors(after, "after", v);
        requireDistinctAnchors(before, "before", v);
        Set<String> anchored = new TreeSet<>(after.keySet());
        anchored.addAll(before.keySet());
        List<String> base = deltaOrder != null ? deltaOrder : parentOrder;
        if (new LinkedHashSet<>(base).size() != base.size()) {
            v.add(Violation.of("stateOrder", "lists a state twice"));
        }
        if (deltaOrder != null) {
            deltaOrder.stream().filter(anchored::contains).forEach(k -> v.add(Violation.of("stateOrder",
                    "lists '" + k + "', which is placed by states." + k + "." + (after.containsKey(k) ? "after" : "before"))));
        }
        List<String> order = new ArrayList<>();
        for (String k : base) {
            if (present.contains(k) && !anchored.contains(k) && !order.contains(k)) {
                order.add(k);
            }
        }
        for (int i = 0; i < parentOrder.size(); i++) {
            String k = parentOrder.get(i);
            if (!present.contains(k) || anchored.contains(k) || order.contains(k)) {
                continue;
            }
            int at = 0;
            for (int j = i - 1; j >= 0; j--) {
                int p = order.indexOf(parentOrder.get(j));
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
                v.add(Violation.of("states." + k + "." + how, present.contains(a)
                        ? "placement cycle through '" + a + "'"
                        : "references unknown state '" + a + "'"));
                order.add(k);
            }
        }
        return order;
    }

    /** Two states anchored to the same side of one state would have no defined order between them. */
    private static void requireDistinctAnchors(Map<String, String> anchors, String how, List<Violation> v) {
        Map<String, String> seen = new TreeMap<>();
        anchors.forEach((k, a) -> {
            String other = seen.putIfAbsent(a, k);
            if (other != null) {
                v.add(Violation.of("states." + k + "." + how, "states." + other + " is already placed " + how + " '" + a
                        + "'; place one relative to the other"));
            }
        });
    }
}
