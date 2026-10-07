// The workflow subset of an effective configuration, as the state-machine library sees it (spike).
//
// The runtime is the extracted state-machine library: transitions (from, event, to) with an
// optional guard, built once per workspace configuration revision. Its definition adapter (a
// sibling library) loads a small, generic definition document; VEC compiles its resolved
// configuration to that document with this pure function. The generic format knows states,
// initial states, end states with an opaque outcome label, transitions and guard *names*. It
// knows nothing of categories, boards, item types, cadence or locks, which stay VEC's.
//
//   - a state's enterFrom lists its allowed sources; absent means every other state;
//   - the event of a transition is its target state key (a tracker move is "go to <state>");
//   - every transition leaving a gate state carries the guard "gate:<state>", bound by the host
//     to "a decision with enough approvals is recorded"; go, recycle and kill are all decisions;
//   - END_STATE states are listed with their outcome. Whether an end state may be left (reopen)
//     is VEC-15's rule; the projection lists whatever enterFrom allows.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Workflow {

    private Workflow() {
    }

    static Map<String, Object> definition(Map<String, Object> effective) {
        Map<String, Object> states = Json.obj(effective.get("states"));
        List<String> order = Json.strings(effective.get("stateOrder"));
        Map<String, Object> outStates = new LinkedHashMap<>();
        List<Object> initial = new ArrayList<>();
        List<Object> transitions = new ArrayList<>();
        for (String k : order) {
            Map<String, Object> st = Json.obj(states.get(k));
            Map<String, Object> s = new LinkedHashMap<>();
            if ("END_STATE".equals(st.get("category"))) {
                s.put("end", st.get("outcome"));
            }
            outStates.put(k, s);
            if ("START_STATE".equals(st.get("category"))) {
                initial.add(k);
            }
        }
        for (String to : order) {
            Object enterFrom = Json.obj(states.get(to)).get("enterFrom");
            for (String from : enterFrom == null ? order : Json.strings(enterFrom)) {
                if (from.equals(to)) {
                    continue;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("from", from);
                t.put("event", to);
                t.put("to", to);
                if (Json.obj(states.get(from)).containsKey("gate")) {
                    t.put("guard", "gate:" + from);
                }
                transitions.add(t);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("states", outStates);
        out.put("initial", initial);
        out.put("transitions", transitions);
        return out;
    }
}
