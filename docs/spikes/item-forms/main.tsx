// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike): ./run.sh demo, then http://localhost:5299
import { StrictMode, useMemo, useState } from "react";
import { createRoot } from "react-dom/client";
import "../../../web/src/styles/tokens.css";
import "./demo.css";
import { EditItem, MoveItem, NewItem, NewProject } from "./Flows";
import type { Effective } from "./forms";
import { MockServer } from "./mockServer";
import type { Locale } from "./messages";
import scrumJson from "./templates/scrum.effective.json";
import stageGateJson from "./templates/stage-gate.effective.json";

const TEMPLATES: Record<string, Effective> = { scrum: scrumJson as unknown as Effective, "stage-gate": stageGateJson as unknown as Effective };
type Form = "project" | "new" | "edit" | "move";

function seeded(key: string): MockServer {
  const cfg = TEMPLATES[key];
  const server = new MockServer(cfg, key === "scrum" ? "WEB" : "NPD");
  if (key === "scrum") {
    server.seed("todo", "Accounts", { type: "epic", priority: "high" });
    server.seed("todo", "Sign in with a passkey", { type: "story", priority: "medium", parentId: "WEB-1" });
  } else {
    server.seed("scoping", "Enter the Nordic market", { type: "project", risk: "medium" });
    server.seed("scoping", "Pilot in one city", { type: "milestone", targetLaunch: "2027-03-01", parentId: "NPD-1" });
  }
  return server;
}

function Demo() {
  const [template, setTemplate] = useState("scrum");
  const [formKind, setFormKind] = useState<Form>("new");
  const [locale, setLocale] = useState<Locale>("en");
  const [to, setTo] = useState("in-progress");
  const [generation, setGeneration] = useState(0);
  const cfg = TEMPLATES[template];
  const server = useMemo(() => seeded(template), [template, generation]); // eslint-disable-line react-hooks/exhaustive-deps
  const [log, setLog] = useState("");
  const target = server.all()[1];
  const done = (what: string) => (item: { key: string; version: number; state?: string }) =>
    setLog(`${what} ${item.key} at version ${item.version}${item.state ? " in " + cfg.states[item.state].name : ""}`);

  return (
    <main className="demo" lang={locale}>
      <p className="demo-note">VEC-67 throwaway prototype: forms rendered from a workspace's effective configuration, validated by the same rules as the (mock) server.</p>
      <div className="demo-bar">
        <label>Template<select value={template} onChange={(e) => { setTemplate(e.target.value); setTo(e.target.value === "scrum" ? "in-progress" : "gate-1"); setLog(""); }}>
          <option value="scrum">Scrum</option><option value="stage-gate">Stage-Gate</option></select></label>
        <label>Form<select value={formKind} onChange={(e) => { setFormKind(e.target.value as Form); setGeneration(generation + 1); setLog(""); }}>
          <option value="project">New Project (VEC-10)</option><option value="new">New Item</option>
          <option value="edit">Edit Item</option><option value="move">Move (transition prompt)</option></select></label>
        {formKind === "move" && <label>Move {target.key} to<select value={to} onChange={(e) => { setTo(e.target.value); setGeneration(generation + 1); }}>
          {cfg.stateOrder.map((s) => <option key={s} value={s}>{cfg.states[s].name}</option>)}</select></label>}
        <label>Language<select value={locale} onChange={(e) => setLocale(e.target.value as Locale)}>
          <option value="en">English</option><option value="it">Italiano</option></select></label>
      </div>
      {formKind === "project" && <NewProject key={generation} templates={[{ key: "scrum", name: "Scrum" }, { key: "stage-gate", name: "Stage-Gate" }]} taken={["WEB", "NPD"]} locale={locale} onDone={(b) => setLog(`would POST /api/v1/workspaces ${JSON.stringify(b)}`)} />}
      {formKind === "new" && <NewItem key={template + generation} cfg={cfg} server={server} column={cfg.stateOrder[0]} locale={locale} onDone={done("created")} />}
      {formKind === "edit" && <EditItem key={template + generation} cfg={cfg} server={server} itemKey={target.key} locale={locale} onDone={done("saved")} />}
      {formKind === "move" && <MoveItem key={template + to + generation} cfg={cfg} server={server} itemKey={target.key} to={to} locale={locale} onDone={done("moved")} />}
      {log && <p role="status" className="demo-note">{log}</p>}
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<StrictMode><Demo /></StrictMode>);
