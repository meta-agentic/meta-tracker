package io.vectis.domain.template;

import static io.vectis.domain.template.TemplateJson.obj;
import static io.vectis.domain.template.TemplateJson.strings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workflow subset of an effective configuration, as the state-machine library's definition
 * adapter loads it: states, initial states, end states with an opaque outcome label, transitions
 * and guard names. It knows nothing of categories, boards, item types, cadence or locks.
 * <ul>
 * <li>a state's {@code enterFrom} lists its allowed sources; absent means every other state;</li>
 * <li>the event of a transition is its target state key (a tracker move is "go to &lt;state&gt;");</li>
 * <li>every transition leaving a gate state carries the guard {@code gate:<state>}, which the host
 *     binds to "a decision with enough approvals is recorded";</li>
 * <li>END_STATE states carry their outcome. Whether one may be left is the host's rule; the
 *     definition lists whatever {@code enterFrom} allows.</li>
 * </ul>
 */
public final class Workflow {

    private Workflow() {
    }

    /** The definition document for {@code effective}, in effective state order. */
    public static Map<String, Object> definition(Map<String, Object> effective) {
        Map<String, Object> states = obj(effective.get("states"));
        List<String> order = strings(effective.get("stateOrder"));
        Map<String, Object> outStates = new LinkedHashMap<>();
        List<Object> initial = new ArrayList<>();
        List<Object> transitions = new ArrayList<>();
        for (String k : order) {
            Map<String, Object> st = obj(states.get(k));
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
            Object enterFrom = obj(states.get(to)).get("enterFrom");
            for (String from : enterFrom == null ? order : strings(enterFrom)) {
                if (from.equals(to)) {
                    continue;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("from", from);
                t.put("event", to);
                t.put("to", to);
                if (obj(states.get(from)).containsKey("gate")) {
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
