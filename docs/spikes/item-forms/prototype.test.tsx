// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike). Run with ./run.sh test.

import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import conformance from "./conformance.json";
import { EditItem, MoveItem, NewItem, NewProject } from "./Flows";
import { check, enterForm, itemForm, type Effective, type Write } from "./forms";
import { MockServer } from "./mockServer";
import scrumJson from "./templates/scrum.effective.json";
import stageGateJson from "./templates/stage-gate.effective.json";

const scrum = scrumJson as unknown as Effective;
const stageGate = stageGateJson as unknown as Effective;
const TEMPLATES: Record<string, Effective> = { scrum, "stage-gate": stageGate };

afterEach(cleanup);

const labels = (form: HTMLElement) => within(form).getAllByText((_, el) => el?.tagName === "LABEL").map((l) => l.firstChild?.textContent);
const form = () => document.querySelector("form") as HTMLElement;
const field = (path: string) => document.querySelector(`[name="${path}"]`) as HTMLInputElement;

describe("F: New Item and Edit Item render from either template", () => {
  it("F1 Scrum New Item: a story shows its fields, the scale as a choice, the default filled in", () => {
    render(<NewItem cfg={scrum} server={new MockServer(scrum, "WEB")} column="todo" locale="en" />);
    expect(labels(form())).toEqual(["Title", "Type", "Parent", "Description", "Story points", "Priority"]);
    expect(field("fields.type").value).toBe("story");
    expect(field("fields.priority").value).toBe("medium");
    expect([...field("fields.storyPoints").querySelectorAll("option")].map((o) => o.value)).toEqual(["", "0", "1", "2", "3", "5", "8", "13", "21"]);
  });

  it("F2 Scrum New Item: switching to a task drops the fields a task does not have", () => {
    render(<NewItem cfg={scrum} server={new MockServer(scrum, "WEB")} column="todo" locale="en" />);
    fireEvent.change(field("fields.type"), { target: { value: "task" } });
    expect(labels(form())).toEqual(["Title", "Type", "Parent", "Description"]);
  });

  it("F3 Stage-gate New Item: a project shows the stage-gate fields, a date input included", () => {
    render(<NewItem cfg={stageGate} server={new MockServer(stageGate, "NPD")} column="idea" locale="en" />);
    expect(labels(form())).toEqual(["Title", "Type", "Description", "Business case", "Risk", "Target launch"]);
    expect(field("fields.targetLaunch").type).toBe("date");
    expect(field("fields.businessCase").tagName).toBe("TEXTAREA");
  });

  it("F4 Stage-gate New Item: a milestone's target date is marked required and refused when empty", () => {
    render(<NewItem cfg={stageGate} server={new MockServer(stageGate, "NPD")} column="idea" locale="en" />);
    fireEvent.change(field("fields.type"), { target: { value: "milestone" } });
    fireEvent.change(field("title"), { target: { value: "Pilot" } });
    expect(field("fields.targetLaunch")).toBeRequired();
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(field("fields.targetLaunch")).toHaveAttribute("aria-invalid", "true");
    expect(document.activeElement).toBe(field("fields.targetLaunch"));
  });

  it("F5 Scrum Edit Item: the stored values, no type picker", () => {
    const server = new MockServer(scrum, "WEB");
    const item = server.seed("todo", "Sign in", { type: "story", storyPoints: 5, priority: "high" });
    render(<EditItem cfg={scrum} server={server} itemKey={item.key} locale="en" />);
    expect(labels(form())).toEqual(["Title", "Parent", "Description", "Story points", "Priority"]);
    expect(field("fields.storyPoints").value).toBe("5");
    fireEvent.change(field("fields.storyPoints"), { target: { value: "8" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(server.get(item.key)).toMatchObject({ version: 2, fields: { storyPoints: 8, priority: "high" } });
  });

  it("F6 Stage-gate Edit Item, in Italian: the violation is localized by its code, on its field", () => {
    const server = new MockServer(stageGate, "NPD");
    const item = server.seed("gate-1", "New market", { type: "project", businessCase: "Grow" });
    render(<EditItem cfg={stageGate} server={server} itemKey={item.key} locale="it" />);
    fireEvent.change(field("fields.businessCase"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Salva" }));
    expect(screen.getAllByText("Gate 1: go to development richiede Business case.").length).toBeGreaterThan(0);
    expect(server.get(item.key)!.version).toBe(1);
  });
});

describe("T: a field required on one transition only", () => {
  it("T1 Stage-gate: Scoping to Gate 1 prompts for the business case alone, and sends it with the move", () => {
    const server = new MockServer(stageGate, "NPD");
    const item = server.seed("scoping", "New market", { type: "project" });
    render(<MoveItem cfg={stageGate} server={server} itemKey={item.key} to="gate-1" locale="en" />);
    expect(labels(form())).toEqual(["Business case"]);
    fireEvent.click(screen.getByRole("button", { name: "Move to Gate 1: go to development" }));
    expect(server.get(item.key)!.state).toBe("scoping");
    fireEvent.change(field("fields.businessCase"), { target: { value: "A market worth entering" } });
    fireEvent.click(screen.getByRole("button", { name: "Move to Gate 1: go to development" }));
    expect(server.get(item.key)).toMatchObject({ state: "gate-1", version: 2, fields: { businessCase: "A market worth entering" } });
  });

  it("T2 Stage-gate: Idea to Scoping prompts for nothing", () => {
    const server = new MockServer(stageGate, "NPD");
    const item = server.seed("idea", "New market", { type: "project" });
    render(<MoveItem cfg={stageGate} server={server} itemKey={item.key} to="scoping" locale="en" />);
    expect(document.querySelector("form")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Move to Scoping" }));
    expect(server.get(item.key)!.state).toBe("scoping");
  });

  it("T3 Scrum: To Do to In Progress prompts a story for points but lets a task through", () => {
    expect(enterForm(scrum, "story", { type: "story" }, "in-progress")?.fields.map((f) => f.path)).toEqual(["fields.storyPoints"]);
    expect(enterForm(scrum, "story", { type: "story", storyPoints: 3 }, "in-progress")).toBeNull();
    expect(enterForm(scrum, "task", { type: "task" }, "in-progress")).toBeNull();
    expect(enterForm(scrum, "story", { type: "story" }, "in-review")).toBeNull();
  });
});

describe("V: violations reach the field they name", () => {
  it("V1 a rule only the server can check (the parent) lands on the parent picker", () => {
    const server = new MockServer(scrum, "WEB");
    server.seed("todo", "Not an epic", { type: "story" });
    const answer = server.create({ column: "todo", title: "Child", fields: { type: "story", parentId: "WEB-1" } });
    expect(answer.status).toBe(422);
    expect(answer.status === 422 && answer.problem.violations).toEqual([expect.objectContaining({ field: "fields.parentId", code: "parent" })]);
  });

  it("V2 New Project (VEC-10's shape) on the same renderer: pattern and a taken key on the key field", () => {
    render(<NewProject templates={[{ key: "scrum", name: "Scrum" }, { key: "stage-gate", name: "Stage-Gate" }]} taken={["WEB"]} locale="en" />);
    expect(labels(form())).toEqual(["Template", "Key", "Name"]);
    fireEvent.change(field("template"), { target: { value: "scrum" } });
    fireEvent.change(field("key"), { target: { value: "web" } });
    fireEvent.change(field("name"), { target: { value: "Web" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getAllByText("Key does not have the expected form.").length).toBeGreaterThan(0);
    fireEvent.change(field("key"), { target: { value: "WEB" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getAllByText("Key is already taken.").length).toBeGreaterThan(0);
  });

  it("V3 every form definition names only paths the rules also name", () => {
    for (const cfg of [scrum, stageGate]) {
      for (const type of Object.keys(cfg.itemTypes)) {
        for (const f of itemForm(cfg, type, "create").fields) expect(f.path).toMatch(/^(title|fields\.[a-zA-Z][a-zA-Z0-9-]*)$/);
      }
    }
  });
});

describe("C: the shared conformance cases (Conformance.java runs the same file)", () => {
  for (const c of conformance.cases) {
    it(c.name, () => {
      const got = check(TEMPLATES[c.template], c.write as unknown as Write).map((v) => [v.field, v.code]);
      expect(got).toEqual(c.expect);
    });
  }
});
