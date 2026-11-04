import { beforeEach, describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { App } from "../App";
import { AppProviders } from "../providers/AppProviders";
import { IndexedDbCache } from "../store/cache";
import { createWorkspaceStore, workspaceStore } from "../store/workspaceStore";
import { createWorkspaceApiClient } from "../api/client";
import { readApiConfig } from "../api/config";
import { newestFirst } from "../api/useSprints";
import type { WireSprint } from "../api/wire";
import {
  createRecordedTransport,
  itemFixture,
  itemPage,
  snapshotRecording,
  type Recording,
} from "../test/recordedTransport";

const config = readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "VEC" });

function sprint(key: string, overrides: Partial<WireSprint> = {}): WireSprint {
  return {
    key,
    name: key,
    state: "COMPLETED",
    start: null,
    end: null,
    closed: null,
    goal: null,
    committedPoints: 0,
    donePoints: 0,
    ...overrides,
  };
}

const items = [
  itemFixture({ key: "VEC-2", title: "Wire the client", column: "doing" }),
  itemFixture({ key: "VEC-3", title: "Old work", column: "todo", rank: "n" }),
  itemFixture({ key: "VEC-4", title: "Unscheduled", column: "todo", rank: "o" }),
];

function recording(sprints: WireSprint[], extra: Recording["routes"] = {}): Recording {
  const base = snapshotRecording(items);
  return {
    routes: {
      ...base.routes,
      "/sprints": { body: sprints },
      "/items?limit=200&sprint=VEC-S2": { body: itemPage([items[0]]) },
      "/items?limit=200&sprint=VEC-S1": { body: itemPage([items[1]]) },
      ...extra,
    },
  };
}

function mount(rec: Recording) {
  const recorded = createRecordedTransport(rec);
  render(
    <AppProviders>
      <App
        sync={{
          createClient: () => createWorkspaceApiClient({ config, transport: recorded.transport }),
        }}
      />
    </AppProviders>,
  );
  return recorded;
}

beforeEach(async () => {
  localStorage.clear();
  workspaceStore.getState().reset();
  workspaceStore.getState().setActiveTab("sprint");
  createWorkspaceStore(new IndexedDbCache()).getState().reset();
  await new Promise((resolve) => setTimeout(resolve, 50));
});

describe("newestFirst", () => {
  it("orders by the number in the key, so S10 is newer than S2, dated or not", () => {
    const listed = [
      sprint("VEC-S2"),
      sprint("VEC-S10"),
      sprint("VEC-S1", { start: "2026-10-01" }),
      sprint("VEC-S3", { start: "2026-10-15" }),
    ];
    expect(newestFirst(listed).map((s) => s.key)).toEqual(["VEC-S10", "VEC-S3", "VEC-S2", "VEC-S1"]);
  });
});

describe("Sprint tab", () => {
  it("shows the active sprint as columns under its header, and a closed one where its items ended", async () => {
    mount(
      recording([
        sprint("VEC-S1", { start: "2026-10-01", end: "2026-10-14", closed: "2026-10-15" }),
        sprint("VEC-S2", {
          state: "ACTIVE",
          start: "2026-10-15",
          end: "2026-10-28",
          goal: "Read the vault",
          committedPoints: 5,
          donePoints: 3,
        }),
        sprint("VEC-S3", { state: "FUTURE" }),
      ]),
    );

    const header = await screen.findByRole("region", { name: "VEC-S2" });
    expect(within(header).getByText("Active")).toBeInTheDocument();
    expect(within(header).getByText("Read the vault")).toBeInTheDocument();
    expect(within(header).getByText("5")).toBeInTheDocument();
    expect(within(header).getByText("3")).toBeInTheDocument();
    expect(within(header).getByText(/2026/)).toBeInTheDocument();

    expect(await screen.findByText("Wire the client")).toBeInTheDocument();
    expect(screen.queryByText("Old work")).not.toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 2 }).map((h) => h.textContent)).toEqual(
      expect.arrayContaining(["To Do", "In Progress"]),
    );

    // Newest first.
    const switcher = screen.getByRole("navigation", { name: "Sprints of this workspace" });
    expect(within(switcher).getAllByRole("button").map((b) => b.textContent)).toEqual([
      "VEC-S3",
      "VEC-S2",
      "VEC-S1",
    ]);

    // Read-only: said so, and nothing to drag or edit.
    expect(screen.getByText(/Read-only/)).toBeInTheDocument();
    expect(document.querySelector("[draggable='true']")).toBeNull();

    fireEvent.click(within(switcher).getByRole("button", { name: "VEC-S1" }));
    expect(await screen.findByText("Old work")).toBeInTheDocument();
    expect(screen.queryByText("Wire the client")).not.toBeInTheDocument();
    // A sprint without a start is shown without dates, none made up.
    const closed = screen.getByRole("region", { name: "VEC-S1" });
    expect(within(closed).getByText("Closed")).toBeInTheDocument();
    expect(workspaceStore.getState().activeTab).toBe("sprint");
  });

  it("says there is no active sprint and offers the latest closed one", async () => {
    mount(recording([sprint("VEC-S1"), sprint("VEC-S2", { start: "2026-10-15" })]));

    expect(await screen.findByText("No active sprint")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show VEC-S2, the latest closed sprint" }));
    expect(await screen.findByRole("region", { name: "VEC-S2" })).toBeInTheDocument();
    expect(await screen.findByText("Wire the client")).toBeInTheDocument();
  });

  it("says so when the workspace has no sprints, and when a sprint has no items", async () => {
    mount(recording([]));
    expect(await screen.findByText("No sprints yet")).toBeInTheDocument();
  });

  it("shows an empty sprint as empty", async () => {
    mount(
      recording([sprint("VEC-S2", { state: "ACTIVE" })], {
        "/items?limit=200&sprint=VEC-S2": { body: itemPage([]) },
      }),
    );
    expect(await screen.findByText("No items in this sprint")).toBeInTheDocument();
  });

  it("shows the failure with a retry when the sprints cannot be read", async () => {
    mount(recording([], { "/sprints": { status: 500, statusText: "Server Error" } }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The sprints could not be loaded.");
    await waitFor(() => expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument());
  });
});
