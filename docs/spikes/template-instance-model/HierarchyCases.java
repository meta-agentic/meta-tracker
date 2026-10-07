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

    /** The shipped hierarchy: five built-ins (rebased onto each other by the release build), and one tenant's organisation and team levels; the team follows the organisation automatically. */
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
        r.follow.put("acme-platform", "auto");
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
        check("L1", "stage-gate locks its gates and the entries into and out of them: a level below cannot drop or weaken a gate or rewire those entries, may rename it; a workspace neither; locks accumulate",
                has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": {\"gate\": {\"approvals\": 1}}}"), "t@1").violations(), "states.gate-1.gate: locked by an ancestor template ('states.gate-1.gate')")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": null}"), "t@1").violations(), "states.gate-1: locked by an ancestor template ('states.gate-1.")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"development\": {\"enterFrom\": [\"scoping\"]}}"), "t@1").violations(), "states.development.enterFrom: locked")
                        && Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": {\"name\": \"Investment decision\"}}"), "t@1").ok()
                        && has(Hierarchy.lockViolations(Json.strings(sg.get("locks")), patch("{\"states\": {\"done\": {\"enterFrom\": [\"development\"]}}}")), "states.done.enterFrom: locked")
                        && Json.strings(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"locks\": [\"states.idea\"]"), "t@1").effective().get("locks"))
                        .equals(List.of("settings.gatedDelivery", "states.development.enterFrom", "states.done.enterFrom", "states.gate-1.enterFrom", "states.gate-1.gate", "states.gate-2.enterFrom", "states.gate-2.gate", "states.idea"))
                        && Json.strings(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"locks\": []"), "t@1").effective().get("locks")).size() == 7,
                "");
        Hierarchy.Workspace plat = new Hierarchy.Workspace("PLAT", "acme-platform", 1, h("instance-platform.json"), true, Map.of());
        check("L2", "an organisation's lock binds every team and workspace below it: QA cannot be loosened, Done's entry cannot be widened",
                Hierarchy.resolveWorkspace(r, plat).ok()
                        && has(Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"qa\": {\"wipLimit\": 9}}}")).violations(), "states.qa.wipLimit: locked by an ancestor template ('states.qa')")
                        && has(Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"done\": {\"enterFrom\": [\"qa\", \"in-review\"]}}}")).violations(), "states.done.enterFrom: locked")
                        && Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"in-review\": {\"name\": \"Peer Review\"}}}")).ok(),
                Hierarchy.writeWorkspaceDelta(r, plat, patch("{\"states\": {\"qa\": {\"wipLimit\": 9}}}")).violations());

        lockBypasses(r);
        MethodologyCases.run(r);
    }

    /** The ways round a lock or a gate an independent review found, each now refused (L3, L4), and the release build (H8). */
    static void lockBypasses(Hierarchy.Registry r) throws Exception {
        Map<String, Object> scrum = r.resolved.get("scrum@1");
        String gated = "\"states\": {\"in-review\": {\"gate\": {\"approvals\": 2}}}";
        Map<String, Object> deepLock = derived("org", 1, "scrum", 1, gated + ", \"locks\": [\"states.in-review.gate.approvals\"]");
        Map<String, Object> typoLock = derived("org", 1, "scrum", 1, "\"locks\": [\"states.in-progress.wipLimt\"]");
        Map<String, Object> org = derived("org", 1, "scrum", 1, gated + ", \"locks\": [\"states.in-review.gate\", \"stateOrder\"]");
        Resolver.Resolution orgRes = Hierarchy.derive(scrum, org, "org@1");
        Map<String, Object> orgEff = orgRes.effective();
        String xs = "\"category\": \"IN_PROGRESS\"";
        check("L3", "no way round a lock: a lock below member level or on a misspelt member is refused; setting the gate object hits a lock on it; a stateOrder lock holds against anchors, additions and removals, not only stateOrder",
                has(Hierarchy.derive(scrum, deepLock, "org@1").violations(), "locks: 'states.in-review.gate.approvals' is deeper than a member")
                        && has(Hierarchy.derive(scrum, typoLock, "org@1").violations(), "locks: 'states.in-progress.wipLimt' names a member states elements do not have")
                        && orgRes.ok()
                        && has(Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"states\": {\"in-review\": {\"gate\": {\"approvals\": 1}}}"), "t@1").violations(), "states.in-review.gate: locked")
                        && has(Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"states\": {\"done\": {\"before\": \"todo\"}}"), "t@1").violations(), "stateOrder: locked by an ancestor template; its value would change")
                        && has(Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"states\": {\"qa\": {\"name\": \"QA\", " + xs + ", \"after\": \"in-review\"}}"), "t@1").violations(), "stateOrder: locked")
                        && has(Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"states\": {\"backlog\": null}"), "t@1").violations(), "stateOrder: locked")
                        && has(Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"stateOrder\": [\"todo\", \"backlog\", \"in-progress\", \"in-review\", \"done\", \"no-go\"]"), "t@1").violations(), "stateOrder: locked")
                        && Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"states\": {\"todo\": {\"name\": \"Next\"}}"), "t@1").ok(),
                Hierarchy.derive(orgEff, derived("t", 1, "org", 1, "\"states\": {\"done\": {\"before\": \"todo\"}}"), "t@1").violations());

        Map<String, Object> sg = r.resolved.get("stage-gate@1");
        String end = "\"category\": \"END_STATE\", \"outcome\": \"DELIVERED\"";
        String sgx = "\"states\": {\"no-go\": {\"name\": \"Shipped\", \"outcome\": \"DELIVERED\"}}";
        String ownGate = "\"states\": {\"fast-track\": {\"name\": \"Fast track\", \"category\": \"IN_PROGRESS\", \"after\": \"scoping\", \"enterFrom\": [\"scoping\"], \"gate\": {\"approvals\": 1}},"
                + " \"shipped\": {\"name\": \"Shipped\", " + end + ", \"after\": \"done\", \"enterFrom\": [\"fast-track\"]}}, \"locks\": [\"states.fast-track.gate\"]";
        String scopingGate = "\"states\": {\"scoping\": {\"gate\": {\"approvals\": 1}},"
                + " \"shipped\": {\"name\": \"Shipped\", " + end + ", \"after\": \"done\", \"enterFrom\": [\"scoping\"]}}";
        Map<String, Object> hybridGated = derived("hg", 1, "hybrid", 1, "\"settings\": {\"gatedDelivery\": true}, \"locks\": [\"states.release-review.gate\"]");
        check("L4", "gated delivery: a level below stage-gate cannot open an ungated way to a DELIVERED end by a changed outcome, a recategorised stage or a new end state; one behind Gate 2 is fine; the invariant itself is locked",
                has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, sgx), "t@1").violations(),
                        "settings.gatedDelivery: 'idea' reaches the DELIVERED end state 'no-go' without passing a locked gate")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"scoping\": {" + end + "}}"), "t@1").violations(), "reaches the DELIVERED end state 'scoping'")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"shipped\": {\"name\": \"Shipped\", " + end + ", \"after\": \"done\"}}"), "t@1").violations(), "reaches the DELIVERED end state 'shipped'")
                        && Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"shipped\": {\"name\": \"Shipped\", " + end + ", \"after\": \"done\", \"enterFrom\": [\"gate-2\"]}}"), "t@1").ok()
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"settings\": {\"gatedDelivery\": false}"), "t@1").violations(), "settings.gatedDelivery: locked")
                        && has(Resolver.resolve(Hierarchy.strip(scrum), patch("{\"settings\": {\"gatedDelivery\": true}}")).violations(), "settings.gatedDelivery: 'backlog' reaches the DELIVERED end state 'done'"),
                Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, sgx), "t@1").violations());
        check("L5", "only gates an ancestor locked count: a team's own gate (even self-locked) or a weak gate on the unlocked Scoping state opens no way to a new DELIVERED end, nor does rewiring Gate 2 to follow Idea; a level that enables the rule counts the gates it locks itself",
                has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, ownGate), "t@1").violations(), "'idea' reaches the DELIVERED end state 'shipped' without passing a locked gate")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, scopingGate), "t@1").violations(), "'idea' reaches the DELIVERED end state 'shipped' without passing a locked gate")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-2\": {\"enterFrom\": [\"idea\"]}}"), "t@1").violations(), "states.gate-2.enterFrom: locked by an ancestor template")
                        && has(Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, "\"states\": {\"gate-1\": {\"enterFrom\": [\"idea\"]}}"), "t@1").violations(), "states.gate-1.enterFrom: locked by an ancestor template")
                        && Hierarchy.derive(r.resolved.get("hybrid@1"), hybridGated, "hg@1").ok()
                        && has(Resolver.resolve(Hierarchy.strip(r.resolved.get("hybrid@1")), patch("{\"settings\": {\"gatedDelivery\": true}}")).violations(), "'backlog' reaches the DELIVERED end state 'done' without passing a locked gate"),
                Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, ownGate), "t@1").violations() + " " + Hierarchy.derive(sg, derived("t", 1, "stage-gate", 1, scopingGate), "t@1").violations());

        Hierarchy.Registry rb = shipped();
        Map<String, Object> base2 = Json.obj(Json.mergePatch(h("base.v1.json"), patch("{\"version\": 2, \"states\": {\"todo\": {\"name\": \"Ready\"}}}")));
        Propagation.Release rel = Propagation.release(rb, List.of(), base2);
        List<String> shipped = rel.files().stream().map(f -> f.get("template") + "@" + f.get("version")).toList();
        boolean sameDelta = rel.files().stream().skip(1).allMatch(f -> Hierarchy.sections(f).equals(Hierarchy.sections(rb.get((String) f.get("template"), 1).document())));
        check("H8", "built-in edges rebase at build time: a release of base@2 adds one reviewed file per rebased built-in (canonical, delta unchanged); runtime propagation never publishes a built-in",
                shipped.equals(List.of("base@2", "kanban@2", "scrum@2", "stage-gate@2", "hybrid@2")) && sameDelta
                        && rel.files().stream().allMatch(f -> Json.write(Json.parse(Json.write(f))).equals(Json.write(f)))
                        && Propagation.propagate(rb, List.of(), "base", 2, "base@2").stream().noneMatch(l -> l.contains(" -> "))
                        && has(Propagation.propagate(shippedWithout(), List.of(), "base", 2, "base@2"), "template kanban@1: built-in, rebased by the release build, not at runtime"),
                rel);
    }

    /** The shipped hierarchy plus a base@2 published without a release build: built-ins left behind. */
    static Hierarchy.Registry shippedWithout() throws Exception {
        Hierarchy.Registry r = shipped();
        Hierarchy.publish(r, Hierarchy.SYSTEM, Json.obj(Json.mergePatch(h("base.v1.json"), patch("{\"version\": 2}"))));
        return r;
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
        Propagation.Release rel = Propagation.release(r1, List.of(p1, s1), base2);
        System.out.println("== release of base@2 (To Do renamed Ready): files the build adds, then the runtime plan; acme set to follow automatically, S1 manual");
        rel.files().forEach(f -> System.out.println("  file " + f.get("template") + ".v" + f.get("version") + ".json  extends " + Json.compact(f.get("extends"))));
        rel.plan().forEach(l -> System.out.println("  " + l));
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
