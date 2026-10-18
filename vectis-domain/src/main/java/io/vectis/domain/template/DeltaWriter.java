package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a write of a new delta must pass before it is stored: it resolves cleanly over its parent,
 * its {@code stateOrder} names only states that exist, and no element still in use leaves the
 * effective configuration (ADR-VEC-03, VEC-45 §4).
 *
 * <p>Occupancy counts what uses an element, keyed {@code "states.<key>"}, {@code "itemTypes.<key>"}
 * and, for links under a parent type, {@code "itemTypes.<type>.parents.<parent type>"}. A missing
 * key counts as zero.
 */
public final class DeltaWriter {

    private DeltaWriter() {
    }

    /**
     * Replace {@code oldDelta} with {@code newDelta} over {@code parent}. The result carries the
     * new effective configuration and every reason the write is refused; it may be stored only
     * when {@link Resolution#ok()}. The removal check runs only on a delta that resolves, so a
     * malformed delta is never reported as orphaning items it would not really remove.
     */
    public static Resolution write(Map<String, Object> parent, Map<String, Object> oldDelta,
                                   Map<String, Object> newDelta, Map<String, Integer> occupancy) {
        Resolution r = LevelResolver.resolve(parent, newDelta);
        List<Violation> v = new ArrayList<>(r.violations());
        Map<String, Object> states = obj(r.effective().get("states"));
        for (String k : strings(newDelta.get("stateOrder"))) {
            if (!states.containsKey(k)) {
                v.add(Violation.of("stateOrder", "references unknown state '" + k + "'"));
            }
        }
        if (v.isEmpty()) {
            v.addAll(checkRemovals(LevelResolver.resolve(parent, oldDelta).effective(), r.effective(), occupancy));
        }
        return new Resolution(r.effective(), v);
    }

    /**
     * An element may leave the effective configuration only when nothing uses it, and an item
     * type's parents may narrow only when no link sits under a parent it drops: either would
     * orphan items nobody moved.
     */
    public static List<Violation> checkRemovals(Map<String, Object> before, Map<String, Object> after,
                                                Map<String, Integer> occupancy) {
        List<Violation> v = new ArrayList<>();
        for (String s : List.of("states", "itemTypes")) {
            Map<String, Object> now = obj(after.get(s));
            for (String k : obj(before.get(s)).keySet()) {
                int n = occupancy.getOrDefault(s + "." + k, 0);
                if (!now.containsKey(k) && n > 0) {
                    v.add(Violation.of(s + "." + k, "still used by " + n + " item(s); move or retype them first"));
                }
            }
        }
        Map<String, Object> types = obj(after.get("itemTypes"));
        Map<String, Object> wasTypes = obj(before.get("itemTypes"));
        for (String k : new TreeSet<>(types.keySet())) {
            List<String> now = strings(obj(types.get(k)).get("parents"));
            for (String p : strings(obj(wasTypes.get(k)).get("parents"))) {
                int n = occupancy.getOrDefault("itemTypes." + k + ".parents." + p, 0);
                if (!now.contains(p) && n > 0) {
                    v.add(Violation.of("itemTypes." + k + ".parents", "no longer allows '" + p + "' while " + n + " "
                            + k + " item(s) sit under one; move them first"));
                }
            }
        }
        return v;
    }
}
