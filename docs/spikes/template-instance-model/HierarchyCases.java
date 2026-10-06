// Cases for the template hierarchy (VEC-66): N-level resolution, publishing, locks, the four
// worked methodology templates and their workflow definitions. Propagation is in
// PropagationCases.java. Run through TemplateModelSpike.java, which calls run() after VEC-45's cases.

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class HierarchyCases extends Cases {

    static final List<String> SYSTEM_FILES = List.of("base.v1.json", "kanban.v1.json", "scrum.v1.json", "hybrid.v1.json", "stage-gate.v1.json");
    static final List<String> ACME_FILES = List.of("acme.v1.json", "acme-platform.v1.json");

    static Map<String, Object> h(String file) throws Exception {
        return load("hierarchy/" + file);
    }

    /** The shipped hierarchy: five built-ins that follow each other automatically, and one tenant's organisation and team levels. */
    static Hierarchy.Registry shipped() throws Exception {
        Hierarchy.Registry r = new Hierarchy.Registry();
        for (String f : SYSTEM_FILES) {
            List<String> v = Hierarchy.publish(r, Hierarchy.SYSTEM, h(f));
            if (!v.isEmpty()) {
                throw new IllegalStateException(f + " " + v);
            }
        }
        for (String f : ACME_FILES) {
            List<String> v = Hierarchy.publish(r, "acme", h(f));
            if (!v.isEmpty()) {
                throw new IllegalStateException(f + " " + v);
            }
        }
        List.of("kanban", "scrum", "hybrid", "stage-gate", "acme-platform").forEach(k -> r.follow.put(k, "auto"));
        return r;
    }

    static Map<String, Object> res(Hierarchy.Registry r, String ref) {
        return r.resolved.get(ref);
    }

    /** A derived document: identity plus a delta, as a level is authored. */
    static Map<String, Object> derived(String key, long version, String parent, long parentVersion, String sections) {
        return patch("{\"template\": \"" + key + "\", \"version\": " + version + ", \"name\": \"" + key + "\","
                + " \"extends\": {\"template\": \"" + parent + "\", \"version\": " + parentVersion + "}"
                + (sections.isBlank() ? "" : ", " + sections) + "}");
    }

    static Map<String, Object> stripAnchors(Map<String, Object> eff) {
        return Hierarchy.strip(eff);
    }

    static void run() throws Exception {
        Hierarchy.Registry r = shipped();
        worked(r);

        section("hierarchy: resolution");
        List<String> files = new ArrayList<>(SYSTEM_FILES);
        files.addAll(ACME_FILES);
        files.add("instance-platform.json");
        boolean canonical = true;
        for (String f : files) {
            String bytes = Files.readString(dir.resolve("hierarchy/" + f));
            canonical &= Json.write(Json.parse(bytes)).equals(bytes);
        }
        boolean orderFree = true;
        for (String f : List.of("scrum.v1.json", "stage-gate.v1.json", "acme.v1.json", "acme-platform.v1.json")) {
            Map<String, Object> doc = h(f);
            Map<String, Object> parent = res(r, ((String) Json.obj(doc.get("extends")).get("template")) + "@1");
            orderFree &= Hierarchy.derive(reversed(parent), reversed(doc), "x").effective()
                    .equals(Hierarchy.derive(parent, doc, "x").effective());
        }
        check("H1", "every level of the shipped hierarchy publishes and resolves on its own; files canonical; member order irrelevant",
                r.versions.size() == 7 && canonical && orderFree
                        && Resolver.resolve(Hierarchy.strip(res(r, "acme-platform@1")), h("instance-platform.json")).ok(),
                r.versions.keySet());

        Map<String, Object> scrum45 = load("scrum.v1.json");
        Map<String, Object> kanban45 = load("kanban.v1.json");
        check("H2", "the hierarchy's scrum and kanban give VEC-45's single-level boards: order, states, item types, fields, settings",
                sameBoard(res(r, "scrum@1"), eff(scrum45, Map.of())) && sameBoard(res(r, "kanban@1"), eff(kanban45, Map.of())),
                res(r, "scrum@1"));

        Map<String, Object> hold = derived("hold", 1, "scrum", 1,
                "\"states\": {\"on-hold\": {\"name\": \"On Hold\", \"category\": \"IN_PROGRESS\", \"after\": \"in-progress\"}}");
        Resolver.Resolution h3 = Hierarchy.derive(res(r, "scrum@1"), hold, "hold@1");
        Resolver.Resolution h3unstripped = Resolver.resolve(res(r, "scrum@1"), Hierarchy.sections(hold));
        check("H3", "anchors are level-local: scrum anchors In Review after In Progress, a child anchors On Hold there too; no collision, the child's is nearest",
                h3.ok() && order(h3.effective()).equals(List.of("backlog", "todo", "in-progress", "on-hold", "in-review", "done", "no-go"))
                        && !state(h3.effective(), "in-review").containsKey("after")
                        && has(h3unstripped.violations(), "already placed after 'in-progress'"),
                h3);

        Hierarchy.Registry r4 = shipped();
        Map<String, Object> pinned = Json.obj(Json.deepCopy(res(r4, "acme-platform@1")));
        Hierarchy.publish(r4, Hierarchy.SYSTEM, Json.obj(Json.mergePatch(h("base.v1.json"),
                patch("{\"version\": 2, \"states\": {\"todo\": {\"name\": \"Ready\"}}}"))));
        Hierarchy.publish(r4, Hierarchy.SYSTEM, derived("scrum", 2, "base", 2, Json.compact(Hierarchy.sections(h("scrum.v1.json"))).replaceAll("^\\{|\\}$", "")));
        boolean cacheIsCache = r4.versions.values().stream().allMatch(x ->
                Hierarchy.resolveChain(Hierarchy.chain(r4, x, new ArrayList<>())).effective().equals(r4.resolved.get(x.ref())));
        check("H4", "pinned at every level: base@2 and scrum@2 change nothing pinned to acme-platform@1; every materialised resolution equals re-resolving its chain",
                res(r4, "acme-platform@1").equals(pinned) && is(name(res(r4, "scrum@2"), "todo"), "Ready")
                        && is(name(res(r4, "acme-platform@1"), "todo"), "To Do") && cacheIsCache,
                r4.versions.keySet());

        Hierarchy.Registry r5 = shipped();
        Map<String, Object> deep = derived("d1", 1, "acme-platform", 1, "");
        Hierarchy.publish(r5, "acme", deep);
        for (int i = 2; i <= 3; i++) {
            Hierarchy.publish(r5, "acme", derived("d" + i, 1, "d" + (i - 1), 1, ""));
        }
        check("H5", "publish refuses: a re-published version, an unknown parent, another tenant's parent or key, a tenant root, no name, too many levels, a level that does not resolve",
                has(Hierarchy.publish(r5, Hierarchy.SYSTEM, h("scrum.v1.json")), "scrum is at version 1; a new version is 2")
                        && has(Hierarchy.publish(r5, "acme", derived("x", 1, "scrum", 9, "")), "scrum@9 is not a published template version")
                        && has(Hierarchy.publish(r5, "globex", derived("g", 1, "acme", 1, "")), "acme@1 belongs to another owner")
                        && has(Hierarchy.publish(r5, "globex", derived("acme", 2, "scrum", 1, "")), "'acme' belongs to acme")
                        && has(Hierarchy.publish(r5, "globex", Json.obj(Json.mergePatch(h("base.v1.json"), patch("{\"template\": \"mine\"}")))), "roots are built-in")
                        && has(Hierarchy.publish(r5, "acme", Json.obj(Json.mergePatch(derived("n", 1, "scrum", 1, ""), patch("{\"name\": null}")))), "n@1: name: a template version needs its own name")
                        && r5.latest("d3") == 1 && has(Hierarchy.publish(r5, "acme", derived("d4", 1, "d3", 1, "")), "more than 7 template levels")
                        && has(Hierarchy.publish(r5, "acme", derived("y", 1, "acme", 1, "\"states\": {\"qa\": {\"enterFrom\": [\"ghost\"]}}")), "locked")
                        && has(Hierarchy.publish(r5, "acme", derived("z", 1, "acme", 1, "\"itemTypes\": {\"story\": null}")), "z@1: settings.defaultItemType: references unknown item type"),
                Hierarchy.publish(r5, "acme", derived("z", 1, "acme", 1, "\"itemTypes\": {\"story\": null}")));

        Map<String, String> prov = Hierarchy.provenance(Hierarchy.chain(r, r.get("acme-platform", 1), new ArrayList<>()), h("instance-platform.json"));
        check("H6", "provenance: every effective value names the level that last set it, root to workspace",
                is(prov.get("states.todo.name"), "base@1") && is(prov.get("states.in-review.name"), "acme@1")
                        && is(prov.get("states.in-review.category"), "scrum@1") && is(prov.get("states.refined.name"), "acme-platform@1")
                        && is(prov.get("cadence.mode"), "scrum@1") && is(prov.get("cadence.sprintDays"), "acme-platform@1")
                        && is(prov.get("states.in-progress.wipLimit"), "workspace") && !prov.containsKey("itemTypes.bug.name")
                        && is(prov.get("itemTypes.task.parents"), "acme@1"),
                prov);

        Map<String, Object> described = patch("{\"states\": {\"qa\": {\"name\": \"QA\", \"description\": \"Manual exploratory testing\","
                + " \"category\": \"IN_PROGRESS\", \"after\": \"in-review\"}}}");
        check("H7", "members are a closed set: a typo is an error, not an ignored policy; description is allowed and survives resolution and round trip",
                has(Resolver.resolve(scrum45, patch("{\"states\": {\"todo\": {\"wiplimit\": 3}}}")).violations(), "states.todo.wiplimit: unknown member")
                        && has(Resolver.resolve(scrum45, patch("{\"cadence\": {\"sprintdays\": 3}}")).violations(), "cadence.sprintdays: unknown member")
                        && Resolver.resolve(scrum45, described).ok()
                        && is(state(eff(scrum45, described), "qa").get("description"), "Manual exploratory testing")
                        && Json.parse(Json.write(eff(scrum45, described))).equals(eff(scrum45, described))
                        && is(res(r, "hybrid@1").get("description"), h("hybrid.v1.json").get("description")),
                Resolver.resolve(scrum45, patch("{\"states\": {\"todo\": {\"wiplimit\": 3}}}")).violations());

        Map<String, Object> schema = load("template.schema.json");
        Map<String, Object> defs = Json.obj(schema.get("$defs"));
        boolean agrees = keys(schema).equals(Validation.MEMBERS.get("document"));
        for (String kind : List.of("states", "itemTypes", "fields", "settings", "cadence", "gate")) {
            agrees &= keys(Json.obj(defs.get(kind))).equals(Validation.MEMBERS.get(kind));
        }
        check("S5", "the published JSON Schema and the validator accept the same members, section by section; the schema is canonical",
                agrees && Json.write(schema).equals(Files.readString(dir.resolve("template.schema.json"))), keys(schema));

        section("hierarchy: locks");
        Map<String, Object> sg = res(r, "stage-gate@1");
        check("L1", "stage-gate locks its gates: a level below cannot drop or weaken a gate or open a path around it, may rename it; a workspace neither; locks accumulate",
                has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": {\"gate\": {\"approvals\": 1}}}"), "t@1").violations(), "states.gate-1.gate: locked by an ancestor template ('states.gate-1.gate')")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": null}"), "t@1").violations(), "states.gate-1: locked by an ancestor template ('states.gate-1.gate')")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"development\": {\"enterFrom\": [\"scoping\"]}}"), "t@1").violations(), "states.development.enterFrom: locked")
                        && Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": {\"name\": \"Investment decision\"}}"), "t@1").ok()
                        && has(Hierarchy.lockViolations(Json.strings(sg.get("locks")), patch("{\"states\": {\"done\": {\"enterFrom\": [\"development\"]}}}")), "states.done.enterFrom: locked")
                        && Json.strings(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"locks\": [\"states.idea\"]"), "t@1").effective().get("locks"))
                        .equals(List.of("states.development.enterFrom", "states.done.enterFrom", "states.gate-1.gate", "states.gate-2.gate", "states.idea"))
                        && Json.strings(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"locks\": []"), "t@1").effective().get("locks")).size() == 4,
                "");
        Hierarchy.Workspace plat = new Hierarchy.Workspace("PLAT", "acme-platform", 1, h("instance-platform.json"), true, Map.of());
        check("L2", "an organisation's lock binds every team and workspace below it: QA cannot be loosened, Done's entry cannot be widened",
                Hierarchy.resolveWorkspace(r, plat).ok()
                        && has(Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"qa\": {\"wipLimit\": 9}}}")).violations(), "states.qa.wipLimit: locked by an ancestor template ('states.qa')")
                        && has(Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"done\": {\"enterFrom\": [\"qa\", \"in-review\"]}}}")).violations(), "states.done.enterFrom: locked")
                        && Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"in-review\": {\"name\": \"Peer Review\"}}}")).ok(),
                Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"qa\": {\"wipLimit\": 9}}}")).violations());

        MethodologyCases.run(r);
    }

    /** The hierarchy's worked example: one workspace four levels down, its provenance, and what a root publish does to it. */
    static void worked(Hierarchy.Registry r) throws Exception {
        Hierarchy.Workspace plat = new Hierarchy.Workspace("PLAT", "acme-platform", 1, h("instance-platform.json"), true, Map.of());
        Map<String, Object> e = Hierarchy.resolveWorkspace(r, plat).effective();
        Map<String, String> prov = Hierarchy.provenance(Hierarchy.chain(r, r.get("acme-platform", 1), new ArrayList<>()), plat.delta);
        System.out.println("== workspace PLAT: base@1 > scrum@1 > acme@1 > acme-platform@1 > instance-platform.json");
        for (String k : order(e)) {
            Map<String, Object> st = state(e, k);
            StringBuilder sb = new StringBuilder(String.format("  %-12s %-13s %-12s", k, Json.compact(st.get("name")), st.get("category")));
            for (String f : List.of("name", "category", "wipLimit", "enterFrom")) {
                if (prov.containsKey("states." + k + "." + f)) {
                    sb.append(" ").append(f).append("<").append(prov.get("states." + k + "." + f));
                }
            }
            System.out.println(sb.toString().stripTrailing());
        }
        System.out.println("  cadence " + Json.compact(e.get("cadence")) + "  locks " + Json.compact(e.get("locks")));
        Hierarchy.Registry r1 = shipped();
        r1.follow.put("acme", "auto");
        Hierarchy.Workspace p1 = new Hierarchy.Workspace("PLAT", "acme-platform", 1, h("instance-platform.json"), true, Map.of());
        Hierarchy.Workspace s1 = new Hierarchy.Workspace("S1", "scrum", 1, patch("{}"), false, Map.of());
        Map<String, Object> base2 = Json.obj(Json.mergePatch(h("base.v1.json"), patch("{\"version\": 2, \"states\": {\"todo\": {\"name\": \"Ready\"}}}")));
        Hierarchy.publish(r1, Hierarchy.SYSTEM, base2);
        System.out.println("== propagation plan: publish base@2 (To Do renamed Ready); acme set to follow automatically, workspace S1 manual");
        Hierarchy.propagate(r1, List.of(p1, s1), "base", 2, "base@2").forEach(l -> System.out.println("  " + l));
        System.out.println("== workflow definition (state-machine library subset): stage-gate@1");
        System.out.print(Json.write(Workflow.definition(r.resolved.get("stage-gate@1"))));
        System.out.println();
    }

    static boolean sameBoard(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> x = stripAnchors(a);
        Map<String, Object> y = stripAnchors(b);
        Map<String, String> ta = new java.util.TreeMap<>();
        Map<String, String> tb = new java.util.TreeMap<>();
        types(x).forEach((k, t) -> ta.put(k, (String) Json.obj(t).get("name")));
        types(y).forEach((k, t) -> tb.put(k, (String) Json.obj(t).get("name")));
        return x.get("states").equals(y.get("states")) && order(x).equals(order(y)) && ta.equals(tb)
                && x.get("fields").equals(y.get("fields")) && x.get("settings").equals(y.get("settings"));
    }

    static List<String> keys(Map<String, Object> schemaObject) {
        return List.copyOf(Json.obj(schemaObject.get("properties")).keySet());
    }
}
