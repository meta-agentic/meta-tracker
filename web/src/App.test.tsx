import { beforeEach, describe, expect, it } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { App } from "./App";
import { AppProviders } from "./providers/AppProviders";
import { IndexedDbCache } from "./store/cache";
import { createWorkspaceStore, workspaceStore } from "./store/workspaceStore";
import { createWorkspaceApiClient } from "./api/client";
import { readApiConfig } from "./api/config";
import { toWorkspaceSnapshot } from "./api/adapter";
import {
  boardFixture,
  createGate,
  createRecordedTransport,
  itemFixture,
  snapshotRecording,
  workspaceFixture,
  type Recording,
} from "./test/recordedTransport";

/**
 * The application's side of VEC-42: the four states the synthetic path never
 * needed, rendered. The transport is recorded, so nothing here needs a server.
 */

const config = readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "VEC" });

/**
 * The components render from the module-level `workspaceStore`, so these tests
 * drive that store rather than an injected one — only the transport is
 * substituted. The cache is reached through a throwaway store over the same
 * IndexedDB, which is the store's own public way to seed and clear it.
 */
function mount(recording: Recording) {
  const recorded = createRecordedTransport(recording);
  const view = render(
    <AppProviders>
      <App
        sync={{
          createClient: () =>
            createWorkspaceApiClient({ config, transport: recorded.transport }),
        }}
      />
    </AppProviders>,
  );
  return { recorded, view };
}

async function clearPersistedWorkspace() {
  createWorkspaceStore(new IndexedDbCache()).getState().reset();
  await new Promise((resolve) => setTimeout(resolve, 50));
}

async function warmCache() {
  const seed = createWorkspaceStore(new IndexedDbCache());
  seed.getState().ingestSnapshot(
    toWorkspaceSnapshot({
      workspace: workspaceFixture,
      boards: [boardFixture],
      items: [itemFixture()],
    }),
  );
  seed.getState().setActiveBoard(boardFixture.id);
  await new Promise((resolve) => setTimeout(resolve, 300));
}

beforeEach(async () => {
  localStorage.clear();
  workspaceStore.getState().reset();
  await clearPersistedWorkspace();
});

describe("App", () => {
  // AC1, AC4
  it("shows a loading state on a cold load, then the served board", async () => {
    const gate = createGate();
    mount({ ...snapshotRecording(), gate: gate.promise });

    expect(screen.getByText("Loading workspace…")).toBeInTheDocument();
    // No board columns while loading: an empty board must not be mistaken for a
    // loaded one that happens to be empty.
    expect(screen.queryByText("To Do")).not.toBeInTheDocument();

    gate.release();

    await waitFor(() => expect(screen.getByText("To Do")).toBeInTheDocument());
    expect(screen.getByText("In Progress")).toBeInTheDocument();
    expect(screen.getByText("Wire the client")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delivery" })).toBeInTheDocument();
  });

  // AC5
  it("revalidates a cached board in place, without remounting it", async () => {
    await warmCache();

    const gate = createGate();
    mount({
      ...snapshotRecording([
        itemFixture(),
        itemFixture({ id: "item-2", key: "VEC-3", rank: "n", title: "Second card" }),
      ]),
      gate: gate.promise,
    });

    // Cached data paints before the server answers.
    await waitFor(() => expect(screen.getByText("Wire the client")).toBeInTheDocument());
    const sectionBeforeRefresh = screen.getByText("To Do").closest("section");
    expect(sectionBeforeRefresh).not.toBeNull();
    expect(screen.queryByText("Second card")).not.toBeInTheDocument();

    gate.release();

    await waitFor(() => expect(screen.getByText("Second card")).toBeInTheDocument());
    // Same DOM node: React reconciled in place. A remount would have replaced
    // it and reset the scroll position with it.
    expect(screen.getByText("To Do").closest("section")).toBe(sectionBeforeRefresh);
  });

  // AC6
  it("keeps a cached board on screen when revalidation fails, and says it is stale", async () => {
    await warmCache();

    mount({ routes: {}, networkError: new TypeError("Failed to fetch") });

    await waitFor(() => expect(screen.getByRole("status")).toBeInTheDocument());
    expect(screen.getByRole("status")).toHaveTextContent(
      "Showing cached data — the last refresh failed.",
    );
    // The board is still rendered. The notice sits beside it, not over it.
    expect(screen.getByText("To Do")).toBeInTheDocument();
    expect(screen.getByText("Wire the client")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  // AC7
  it("shows an error with a working retry when there is no cache to fall back on", async () => {
    const { recorded } = mount({
      routes: {},
      networkError: new TypeError("Failed to fetch"),
    });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The workspace could not be loaded.");
    expect(screen.queryByText("To Do")).not.toBeInTheDocument();

    recorded.play(snapshotRecording());
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(screen.getByText("To Do")).toBeInTheDocument());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("offers no retry for a failure repeating cannot fix", async () => {
    mount({
      routes: { "/workspaces/VEC": { body: { id: 1, key: "VEC", name: "Vectis" } } },
    });

    await screen.findByRole("alert");
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  // AC3
  it("renders the timeline profiler with no server and no workspace data", async () => {
    mount({ routes: {}, networkError: new TypeError("Failed to fetch") });

    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Timeline profiler" }));

    expect(screen.getByRole("button", { name: "Run dual-axis profile" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});
