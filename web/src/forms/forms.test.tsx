import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import scrumJson from "../../../vectis-domain/src/test/resources/templates/golden/scrum.v1.resolved.json";
import stageGateJson from "../../../vectis-domain/src/test/resources/templates/golden/stage-gate.v1.resolved.json";
import schemaJson from "../../../vectis-domain/src/main/resources/templates/template.schema.json";
import { ApiError, type WireItem, type WorkspaceApiClient } from "../api";
import { AppProviders } from "../providers/AppProviders";
import { workspaceStore } from "../store/workspaceStore";
import { ConfiguredForm } from "./ConfiguredForm";
import { enterForm, itemForm, toBody } from "./definition";
import { FIELD_ICONS } from "./fieldIcons";
import { EditItemForm, MoveItemControl, NewItemControl } from "./ItemForms";
import { ItemWritesProvider } from "./ItemWrites";
import type { Effective, FieldViolation } from "./rules";

const scrum = scrumJson as unknown as Effective;
const stageGate = stageGateJson as unknown as Effective;

function item(over: Partial<WireItem> & { fields: WireItem["fields"] }): WireItem {
  return { key: "W-1", title: "Sign in", board: "W", column: "todo", rank: "a", version: 1, sprint: null, ...over };
}

function problem(status: number, violations: FieldViolation[] = [], detail = "refused"): ApiError {
  return new ApiError("http", `${status}`, {
    status,
    problem: { type: "about:blank", title: "t", status, detail, violations },
  });
}

/** A workspace client that answers the forms' reads and records their writes. */
function fakeClient(cfg: Effective, over: Partial<WorkspaceApiClient> = {}, readOnly = false) {
  const client = {
    workspaceKey: "W",
    getWorkspace: vi.fn(async () => ({ key: "W", name: "W", template: { key: "scrum", version: 1 }, readOnly })),
    getConfiguration: vi.fn(async () => ({ revision: 1, effective: cfg as unknown as Record<string, never> })),
    getItem: vi.fn(async () => item({ fields: { type: "story", storyPoints: 5, priority: "high" } })),
    createItem: vi.fn(async () => item({ fields: {} })),
    editItem: vi.fn(async () => item({ fields: {} })),
    moveItem: vi.fn(async () => item({ fields: {} })),
    ...over,
  };
  return client as unknown as WorkspaceApiClient & typeof client;
}

function renderWith(client: WorkspaceApiClient, ui: React.ReactNode, refresh = vi.fn()) {
  return render(
    <AppProviders>
      <ItemWritesProvider client={client} refresh={refresh}>
        {ui}
      </ItemWritesProvider>
    </AppProviders>,
  );
}

beforeEach(() => {
  localStorage.clear();
  act(() => workspaceStore.getState().setLocale("en"));
});

afterEach(() => vi.restoreAllMocks());

describe("the form definition", () => {
  it("draws a Scrum story: title, type, parent, then its own fields in its order", () => {
    expect(itemForm(scrum, "story", "create").fields.map((f) => f.path)).toEqual([
      "title", "fields.type", "fields.parentId", "fields.description", "fields.storyPoints", "fields.priority",
    ]);
  });

  it("draws an epic without a parent picker, and an edit without a type picker", () => {
    expect(itemForm(scrum, "epic", "edit").fields.map((f) => f.path)).toEqual(["title", "fields.description", "fields.priority"]);
  });

  it("offers the point scale as a choice sent as a number", () => {
    const points = itemForm(scrum, "story", "create").fields.find((f) => f.path === "fields.storyPoints")!;
    expect(points.options?.map((o) => o.key)).toEqual(["0", "1", "2", "3", "5", "8", "13", "21"]);
    expect(toBody({ fields: [points] }, { "fields.storyPoints": "8" }, "create").fields).toEqual({ storyPoints: 8 });
  });

  it("marks a bug's description required and a story's not", () => {
    const required = (type: string) => itemForm(scrum, type, "create").fields.find((f) => f.path === "fields.description")!.required;
    expect([required("bug"), required("story")]).toEqual([true, false]);
  });

  it("asks a project entering Gate 1 for its business case alone, and nothing on the way to Scoping", () => {
    expect(enterForm(stageGate, "project", { type: "project" }, "gate-1")?.fields.map((f) => f.path)).toEqual(["fields.businessCase"]);
    expect(enterForm(stageGate, "project", { type: "project", businessCase: "Grow" }, "gate-1")).toBeNull();
    expect(enterForm(stageGate, "project", { type: "project" }, "scoping")).toBeNull();
    expect(enterForm(stageGate, "milestone", { type: "milestone" }, "gate-1")).toBeNull();
  });

  it("sends only what an edit changed, and an emptied field as null", () => {
    const def = itemForm(scrum, "story", "edit");
    const initial = { title: "A", "fields.priority": "high", "fields.description": "x" };
    expect(toBody(def, { ...initial, "fields.description": "" }, "edit", initial)).toEqual({ fields: { description: null } });
  });

  it("draws every icon the template schema names", () => {
    const names = (schemaJson as { $defs: { icon: { enum: string[] } } }).$defs.icon.enum;
    expect(Object.keys(FIELD_ICONS).sort()).toEqual([...names].sort());
  });
});

describe("the configured form's accessibility", () => {
  const def = itemForm(scrum, "bug", "create");
  const violations: FieldViolation[] = [
    { field: "fields.description", code: "required", message: "a Bug has a Description" },
    { field: "board", code: null, message: "workspace W has no board X" },
  ];
  const renderForm = (v: FieldViolation[] = violations) =>
    render(
      <AppProviders>
        <ConfiguredForm definition={def} values={{ "fields.type": "bug" }} violations={v} submitLabel="Create"
          onChange={() => {}} onSubmit={() => {}} onCancel={() => {}} />
      </AppProviders>,
    );

  it("binds every label to its input", () => {
    renderForm([]);
    expect(screen.getByLabelText(/^Title/)).toHaveAttribute("name", "title");
    expect(screen.getByLabelText(/^Description/).tagName).toBe("TEXTAREA");
  });

  it("says 'required' in words in the label", () => {
    renderForm([]);
    expect(screen.getByLabelText(/^Description/).closest(".vec-form__field")).toHaveTextContent("Description (required)");
  });

  it("marks a wrong input invalid and describes it by its error", () => {
    renderForm();
    const description = screen.getByLabelText(/^Description/);
    expect(description).toHaveAttribute("aria-invalid", "true");
    expect(description).toHaveAccessibleDescription("Description is required.");
  });

  it("lists every problem in a summary that links to its input, and the ones without an input as text", () => {
    renderForm();
    const summary = screen.getByRole("alert");
    const link = within(summary).getByRole("link", { name: "Description" });
    expect(document.getElementById(link.getAttribute("href")!.slice(1))).toBe(screen.getByLabelText(/^Description/));
    expect(summary).toHaveTextContent("workspace W has no board X");
  });

  it("puts focus on the first wrong input", () => {
    renderForm();
    expect(document.activeElement).toBe(screen.getByLabelText(/^Description/));
  });

  it("draws an icon-only option with its name as accessible name and tooltip", () => {
    renderForm([]);
    const high = screen.getByRole("radio", { name: "High" });
    expect(high.closest("label")).toHaveAttribute("title", "High");
    expect(high.closest("label")).not.toHaveTextContent("High");
    expect(screen.getByRole("radio", { name: "Bug" })).toBeChecked();
    expect(screen.getByRole("group", { name: /Priority/ })).toBeInTheDocument();
  });

  it("translates a violation by its code, and falls back to the server's sentence", () => {
    act(() => workspaceStore.getState().setLocale("it"));
    renderForm([
      { field: "fields.description", code: "required", message: "a Bug has a Description" },
      { field: "title", code: "somethingNew", message: "the server's own words" },
    ]);
    expect(screen.getByLabelText(/^Description/)).toHaveAccessibleDescription("Description è obbligatorio.");
    expect(screen.getByLabelText(/^Titolo/)).toHaveAccessibleDescription("the server's own words");
  });
});

describe("New item, Edit and Move against the API", () => {
  it("creates a story with its points and priority, and refreshes the board", async () => {
    const client = fakeClient(scrum);
    const refresh = vi.fn();
    renderWith(client, <NewItemControl boardKey="W" columnKey="todo" />, refresh);
    fireEvent.click(await screen.findByRole("button", { name: "New item" }));
    fireEvent.change(screen.getByLabelText(/^Title/), { target: { value: "Sign in" } });
    fireEvent.change(screen.getByLabelText(/^Story points/), { target: { value: "5" } });
    fireEvent.click(screen.getByRole("radio", { name: "High" }));
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
    expect(client.createItem).toHaveBeenCalledWith({
      board: "W", column: "todo", title: "Sign in", fields: { type: "story", storyPoints: 5, priority: "high" },
    });
  });

  it("refuses a bug without a description before sending, on its field", async () => {
    const client = fakeClient(scrum);
    renderWith(client, <NewItemControl boardKey="W" columnKey="todo" />);
    fireEvent.click(await screen.findByRole("button", { name: "New item" }));
    fireEvent.click(screen.getByRole("radio", { name: "Bug" }));
    fireEvent.change(screen.getByLabelText(/^Title/), { target: { value: "Crash" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(screen.getByLabelText(/^Description/)).toHaveAccessibleDescription("Description is required.");
    expect(client.createItem).not.toHaveBeenCalled();
  });

  it("puts a violation only the server finds on its field", async () => {
    const client = fakeClient(scrum, {
      createItem: vi.fn(async () => {
        throw problem(422, [{ field: "fields.parentId", code: "parent", message: "not a parent" }]);
      }),
    });
    renderWith(client, <NewItemControl boardKey="W" columnKey="todo" />);
    fireEvent.click(await screen.findByRole("button", { name: "New item" }));
    fireEvent.change(screen.getByLabelText(/^Title/), { target: { value: "Child" } });
    fireEvent.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findByText("This item cannot sit under the chosen parent.", { selector: ".vec-form__error" })).toBeInTheDocument();
  });

  it("edits the points with the version it read", async () => {
    const client = fakeClient(scrum);
    const done = vi.fn();
    renderWith(client, <EditItemForm itemKey="W-1" onDone={done} />);
    fireEvent.change(await screen.findByLabelText(/^Story points/), { target: { value: "8" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(done).toHaveBeenCalled());
    expect(client.editItem).toHaveBeenCalledWith("W-1", 1, { fields: { storyPoints: 8 } });
  });

  it("says when someone else changed the item, and loads their version", async () => {
    const client = fakeClient(scrum, {
      editItem: vi.fn(async () => {
        throw problem(412);
      }),
    });
    renderWith(client, <EditItemForm itemKey="W-1" onDone={() => {}} />);
    fireEvent.change(await screen.findByLabelText(/^Story points/), { target: { value: "8" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(/Someone else changed this item/)).toBeInTheDocument();
    expect(client.getItem).toHaveBeenCalledTimes(2);
  });

  it("prompts for exactly the business case on the way into Gate 1, and moves with it in one write", async () => {
    const client = fakeClient(stageGate, {
      getItem: vi.fn(async () => item({ column: "scoping", fields: { type: "project" } })),
    });
    const columns = stageGate.stateOrder!.map((k) => ({ key: k, name: stageGate.states[k].name }));
    renderWith(client, <MoveItemControl itemKey="W-1" columns={columns} current="scoping" />);
    fireEvent.change(await screen.findByLabelText("Move to"), { target: { value: "gate-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Move" }));
    const prompt = await screen.findByRole("region", { name: /Gate 1: go to development needs more/ });
    expect(within(prompt).getAllByRole("textbox").map((e) => e.getAttribute("name"))).toEqual(["fields.businessCase"]);
    fireEvent.change(within(prompt).getByLabelText(/^Business case/), { target: { value: "Grow" } });
    fireEvent.click(within(prompt).getByRole("button", { name: "Move to Gate 1: go to development" }));
    await waitFor(() => expect(client.moveItem).toHaveBeenCalledWith("W-1", 1, { column: "gate-1", fields: { businessCase: "Grow" } }));
  });

  it("moves to a state that needs nothing without a prompt", async () => {
    const client = fakeClient(stageGate, { getItem: vi.fn(async () => item({ column: "idea", fields: { type: "project" } })) });
    renderWith(client, <MoveItemControl itemKey="W-1" columns={[{ key: "idea", name: "Idea" }, { key: "scoping", name: "Scoping" }]} current="idea" />);
    fireEvent.change(await screen.findByLabelText("Move to"), { target: { value: "scoping" } });
    fireEvent.click(screen.getByRole("button", { name: "Move" }));
    await waitFor(() => expect(client.moveItem).toHaveBeenCalledWith("W-1", 1, { column: "scoping" }));
    expect(screen.queryByRole("region")).toBeNull();
  });

  it("opens the same prompt when the server answers requiredToEnter, and cancel leaves the item", async () => {
    const client = fakeClient(scrum, {
      getItem: vi.fn(async () => item({ fields: { type: "story" } })),
      moveItem: vi.fn(async () => {
        throw problem(422, [{ field: "fields.storyPoints", code: "requiredToEnter", message: "In Progress needs Story points" }]);
      }),
    });
    renderWith(client, <MoveItemControl itemKey="W-1" columns={[{ key: "todo", name: "To Do" }, { key: "in-progress", name: "In Progress" }]} current="todo" />);
    fireEvent.change(await screen.findByLabelText("Move to"), { target: { value: "in-progress" } });
    fireEvent.click(screen.getByRole("button", { name: "Move" }));
    const prompt = await screen.findByRole("region", { name: /In Progress needs more/ });
    expect(within(prompt).getByLabelText(/^Story points/)).toBeInTheDocument();
    fireEvent.click(within(prompt).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("region")).toBeNull();
    expect(client.moveItem).toHaveBeenCalledTimes(1);
  });

  it("shows no write control on a read-only workspace, and says why", async () => {
    const client = fakeClient(scrum, {}, true);
    renderWith(client, <NewItemControl boardKey="W" columnKey="todo" />);
    expect(await screen.findByRole("note")).toHaveTextContent("read-only");
    expect(screen.queryByRole("button", { name: "New item" })).toBeNull();
  });
});
