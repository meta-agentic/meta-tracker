package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.deepCopy;
import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Re-pinning a delta from one parent version to a newer one: the evolution operation of
 * ADR-VEC-03 (VEC-45 §5). The computation is pure, so a dry run and a real upgrade are the same
 * call; only the caller decides whether to store {@link #delta()} and the new pin.
 *
 * @param delta     the rewritten delta, to be stored with the new pin
 * @param effective the effective configuration it resolves to over the new version
 * @param notes     what the upgrade changed or kept, for the reviewer; never a reason to refuse
 * @param conflicts every reason the upgrade is refused; empty when it may be applied
 */
public record Upgrade(Map<String, Object> delta, Map<String, Object> effective, List<Note> notes,
                      List<Violation> conflicts) {

    public Upgrade {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(effective, "effective");
        notes = List.copyOf(notes);
        conflicts = List.copyOf(conflicts);
    }

    /** One thing an upgrade did to the delta, or noticed about the new version. */
    public record Note(String field, String message) {
        @Override
        public String toString() {
            return field + ": " + message;
        }
    }

    public boolean ok() {
        return conflicts.isEmpty();
    }

    /**
     * Upgrade {@code delta} from parent version {@code from} to {@code to}. The delta is rewritten
     * only where the parent's change would otherwise change its meaning:
     * <ul>
     * <li>an override of an element the new version removed is promoted to a full, instance-owned
     *     element (the old values with the override applied), and a promoted state is kept in place;</li>
     * <li>a tombstone for an element the new version also removed is dropped as redundant;</li>
     * <li>an added key the new version now also defines is adopted: kept as an override, so the
     *     delta's values win and the parent fills the rest. A state whose category or outcome
     *     disagrees is refused instead, since adopting it would silently change what reaching it means;</li>
     * <li>a state anchored to a state the new version removed is re-anchored in place;</li>
     * <li>removed states are dropped from an overridden stateOrder.</li>
     * </ul>
     * A state the new version recategorises is always noted, and refused while it holds items whose
     * meaning would change. Anything else (additions, renames, reorders) is inherited by resolving
     * again. Elements are rewritten in key order, so the result never depends on member order.
     *
     * <p>{@code from} and {@code to} are parents as a delta resolves over them: template documents,
     * or materialised versions stripped of placement anchors. A workspace upgrade between two
     * materialised versions goes through {@link #ofWorkspace}, which also enforces their locks.
     */
    public static Upgrade of(Map<String, Object> from, Map<String, Object> to, Map<String, Object> delta,
                             Map<String, Integer> occupancy) {
        Map<String, Object> d = obj(deepCopy(delta));
        List<Note> notes = new ArrayList<>();
        List<Violation> refused = new ArrayList<>();
        Map<String, Object> was = LevelResolver.resolve(from, delta).effective();
        Set<String> promoted = new TreeSet<>();
        for (String s : LevelResolver.KEYED) {
            Map<String, Object> before = obj(from.get(s));
            Map<String, Object> after = obj(to.get(s));
            Map<String, Object> section = obj(d.get(s));
            for (String k : new TreeSet<>(section.keySet())) {
                Object x = section.get(k);
                String p = s + "." + k;
                if (x == null && !after.containsKey(k)) {
                    section.remove(k);
                    notes.add(new Note(p, "template removed it as well; tombstone dropped"));
                } else if (x != null && before.containsKey(k) && !after.containsKey(k)) {
                    section.put(k, TemplateJson.mergePatch(before.get(k), x));
                    notes.add(new Note(p, "template removed it; the instance customised it, so it keeps it as its own"));
                    if (s.equals("states")) {
                        promoted.add(k);
                    }
                } else if (x != null && !before.containsKey(k) && after.containsKey(k)) {
                    adopt(p, obj(x), obj(after.get(k)), s.equals("states"), notes, refused);
                }
            }
            if (d.containsKey(s) && section.isEmpty()) {
                d.remove(s);
            }
        }
        Set<String> survives = obj(LevelResolver.resolve(to, d).effective().get("states")).keySet();
        List<String> listed = strings(d.get("stateOrder"));
        for (String k : promoted) {
            if (!listed.contains(k) && anchorOf(obj(obj(d.get("states")).get(k))) == null) {
                keepInPlace(k, was, survives, d, notes);
            }
        }
        Map<String, Object> ds = obj(d.get("states"));
        for (String k : new TreeSet<>(ds.keySet())) {
            Object x = ds.get(k);
            String a = x == null ? null : anchorOf(obj(x));
            if (a != null && !survives.contains(a)) {
                obj(x).remove("after");
                obj(x).remove("before");
                notes.add(new Note("states." + k, "template removed its anchor '" + a + "'"));
                keepInPlace(k, was, survives, d, notes);
            }
        }
        if (d.containsKey("stateOrder")) {
            List<String> kept = strings(d.get("stateOrder"));
            kept.retainAll(survives);
            d.put("stateOrder", kept);
            List<String> oldOrder = strings(from.get("stateOrder"));
            List<String> newOrder = strings(to.get("stateOrder"));
            oldOrder.retainAll(newOrder);
            newOrder.retainAll(oldOrder);
            if (!oldOrder.equals(newOrder)) {
                notes.add(new Note("stateOrder", "template reordered its states; the instance keeps its own order"));
            }
        }
        Resolution r = LevelResolver.resolve(to, d);
        recategorised(from, to, was, r.effective(), occupancy, notes, refused);
        List<Violation> conflicts = new ArrayList<>(refused);
        conflicts.addAll(r.violations());
        if (conflicts.isEmpty()) {
            conflicts.addAll(DeltaWriter.checkRemovals(was, r.effective(), occupancy));
        }
        return new Upgrade(d, r.effective(), notes, conflicts);
    }

    /**
     * Upgrade a workspace delta from one materialised template version to another: {@link #of}
     * over both as the workspace validates against them, plus the new version's locks, so the
     * rewritten delta is one {@link DeltaWriter#writeWorkspace} over {@code toResolved} accepts.
     * Conflicts are attributed to {@link TemplateResolver#WORKSPACE}; the effective configuration
     * shows the new version's locks.
     */
    public static Upgrade ofWorkspace(Map<String, Object> fromResolved, Map<String, Object> toResolved,
                                      Map<String, Object> delta, Map<String, Integer> occupancy) {
        Upgrade u = of(TemplateHierarchy.forValidation(fromResolved), TemplateHierarchy.forValidation(toResolved), delta, occupancy);
        Resolution res = TemplateResolver.resolveWorkspace(toResolved, u.delta());
        List<Violation> conflicts = new ArrayList<>();
        u.conflicts().forEach(c -> conflicts.add(c.at(TemplateResolver.WORKSPACE)));
        res.violations().stream().filter(x -> !conflicts.contains(x)).forEach(conflicts::add);
        return new Upgrade(u.delta(), res.effective(), u.notes(), conflicts);
    }

    /** An added element the new version now defines too: the delta's values win, the parent fills the rest. */
    private static void adopt(String p, Map<String, Object> mine, Map<String, Object> theirs, boolean state,
                              List<Note> notes, List<Violation> refused) {
        List<String> differ = new ArrayList<>();
        List<String> filled = new ArrayList<>();
        for (String f : new TreeSet<>(theirs.keySet())) {
            if (!mine.containsKey(f)) {
                filled.add(f);
            } else if (!Objects.equals(mine.get(f), theirs.get(f))) {
                differ.add(f);
            }
        }
        notes.add(new Note(p, "template now defines it too; instance values win"
                + (differ.isEmpty() ? "" : " over " + differ) + (filled.isEmpty() ? "" : ", template fills " + filled)));
        if (state && (differ.contains("category") || differ.contains("outcome"))) {
            refused.add(Violation.of(p, "the instance added it as " + kind(mine) + ", the template now defines it as "
                    + kind(theirs) + "; align its category and outcome, or empty and remove the state, before upgrading"));
        }
    }

    /**
     * A change of a state's category or outcome in the new version is always noted. Where it changes
     * what the state means here (the delta does not set its own) and items are in it, the upgrade is
     * refused: they would start or stop counting as delivered without anyone moving them.
     */
    private static void recategorised(Map<String, Object> from, Map<String, Object> to, Map<String, Object> was,
                                      Map<String, Object> now, Map<String, Integer> occupancy,
                                      List<Note> notes, List<Violation> refused) {
        Map<String, Object> oldStates = obj(from.get("states"));
        Map<String, Object> newStates = obj(to.get("states"));
        Map<String, Object> wasStates = obj(was.get("states"));
        for (String k : new TreeSet<>(oldStates.keySet())) {
            String oldKind = kind(obj(oldStates.get(k)));
            if (!newStates.containsKey(k) || !wasStates.containsKey(k) || oldKind.equals(kind(obj(newStates.get(k))))) {
                continue;
            }
            String before = kind(obj(wasStates.get(k)));
            String after = kind(obj(obj(now.get("states")).get(k)));
            String change = "template changes it from " + oldKind + " to " + kind(obj(newStates.get(k)));
            int n = occupancy.getOrDefault("states." + k, 0);
            if (before.equals(after)) {
                notes.add(new Note("states." + k, change + "; the instance's own category and outcome are kept"));
            } else if (n > 0) {
                refused.add(Violation.of("states." + k, change + " while " + n + " item(s) are in it; move them first, or keep "
                        + before + " by setting it in the delta"));
            } else {
                notes.add(new Note("states." + k, change + "; it holds no items"));
            }
        }
    }

    /**
     * Where an imported item lands whose status the configuration does not recognise: the first
     * state, in effective order, with the item's category and, for an END_STATE, its outcome. Null
     * when there is none; the import then refuses the item. An upgrade never falls back: a state
     * that disappears while items are in it refuses the upgrade instead.
     */
    public static String fallback(Map<String, Object> effective, String category, String outcome) {
        Objects.requireNonNull(category, "category");
        Map<String, Object> states = obj(effective.get("states"));
        for (String k : strings(effective.get("stateOrder"))) {
            Map<String, Object> st = obj(states.get(k));
            if (category.equals(st.get("category"))
                    && (!"END_STATE".equals(category) || Objects.equals(outcome, st.get("outcome")))) {
                return k;
            }
        }
        return null;
    }

    /** A state's category, with its outcome when it has one: "END_STATE (DELIVERED)". */
    private static String kind(Map<String, Object> state) {
        return state.get("category") + (state.get("outcome") == null ? "" : " (" + state.get("outcome") + ")");
    }

    /** The state a state is anchored to, by "after" or "before", or null. */
    private static String anchorOf(Map<String, Object> state) {
        return state.get("after") instanceof String a ? a : state.get("before") instanceof String b ? b : null;
    }

    /**
     * Keep an instance-owned state where it was on the board: anchor it after its nearest
     * predecessor (in the pre-upgrade order) that survives or, when nothing before it survives,
     * before its nearest surviving successor. A neighbour placed through the delta's anchors
     * relative to this state is skipped: anchoring to it would close a cycle, and it moves with
     * this state anyway. Only when every other surviving state is placed relative to this one does
     * it fall back to pinning stateOrder, so the delta stays sparse.
     */
    private static void keepInPlace(String k, Map<String, Object> was, Set<String> survives,
                                    Map<String, Object> d, List<Note> notes) {
        List<String> old = strings(was.get("stateOrder"));
        Map<String, Object> states = obj(d.get("states"));
        for (int i = old.indexOf(k) - 1; i >= 0; i--) {
            if (canAnchor(old.get(i), k, survives, states)) {
                obj(states.get(k)).put("after", old.get(i));
                notes.add(new Note("states." + k, "placed after '" + old.get(i) + "' so it stays where it was"));
                return;
            }
        }
        for (int i = old.indexOf(k) + 1; i < old.size(); i++) {
            if (canAnchor(old.get(i), k, survives, states)) {
                obj(states.get(k)).put("before", old.get(i));
                notes.add(new Note("states." + k, "placed before '" + old.get(i) + "' so it stays where it was"));
                return;
            }
        }
        List<String> pinned = new ArrayList<>();
        for (String s : old) {
            if (survives.contains(s) && anchorOf(obj(states.get(s))) == null) {
                pinned.add(s);
            }
        }
        d.put("stateOrder", pinned);
        notes.add(new Note("stateOrder", "pinned so states." + k + " stays where it was"));
    }

    /** Whether k may be anchored to c: c survives and its chain of anchors, as rewritten so far, does not reach k. */
    private static boolean canAnchor(String c, String k, Set<String> survives, Map<String, Object> states) {
        if (!survives.contains(c)) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String s = c; s != null && seen.add(s); s = states.get(s) == null ? null : anchorOf(obj(states.get(s)))) {
            if (s.equals(k)) {
                return false;
            }
        }
        return true;
    }
}
