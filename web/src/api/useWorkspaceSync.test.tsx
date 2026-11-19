import { beforeEach, describe, expect, it } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { MemoryCache, type AsyncCache } from "../store/cache";
import { createWorkspaceStore } from "../store/workspaceStore";
import type { WorkspaceSnapshot } from "../store/types";
import { useWorkspaceSync } from "./useWorkspaceSync";
import { createWorkspaceApiClient } from "./client";
import { readApiConfig } from "./config";
import { toWorkspaceSnapshot } from "./adapter";
import {
  boardFixture,
  createGate,
  createRecordedTransport,
  itemFixture,
  itemPage,
  otherWorkspaceFixture,
  snapshotRecording,
  twoWorkspacesRecording,
  workspaceFixture,
  type Recording,
} from "../test/recordedTransport";

const config = readApiConfig({});

function clientFactory(recorded: ReturnType<typeof createRecordedTransport>) {
  return (workspaceKey: string) =>
    createWorkspaceApiClient({ config, workspaceKey, transport: recorded.transport });
}

function snapshotOf(items = [itemFixture()]): WorkspaceSnapshot {
  return toWorkspaceSnapshot({
    workspace: workspaceFixture,
    boards: [boardFixture],
    items,
  });
}

/**
 * Seeds the cache the way the app does — through a store whose debounced
 * persist actually runs — so a "warm load" test exercises the real cache path
 * rather than a hand-written cache entry.
 */
async function warmCache(cache: AsyncCache, snapshot: WorkspaceSnapshot) {
  const seed = createWorkspaceStore(cache);
  seed.getState().ingestSnapshot(snapshot);
  seed.getState().setActiveBoard(snapshot.boards[0].id);
  await new Promise((resolve) => setTimeout(resolve, 300));
}

function renderSync(cache: AsyncCache, recording: Recording) {
  const store = createWorkspaceStore(cache);
  const recorded = createRecordedTransport(recording);
  const view = renderHook(() =>
    useWorkspaceSync({ workspaceKey: "VEC", store, createClient: clientFactory(recorded) }),
  );
  return { store, recorded, view };
}

beforeEach(() => {
  localStorage.clear();
});

describe("useWorkspaceSync", () => {
  // AC4
  it("shows a loading state on a cold load and replaces it with real data", async () => {
    const { store, view } = renderSync(new MemoryCache(), snapshotRecording());

    expect(view.result.current.status).toBe("loading");
    // While loading, the store holds no boards — so nothing can render an empty
    // board as though it were a loaded empty one.
    expect(Object.keys(store.getState().boardsById)).toHaveLength(0);

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(Object.keys(store.getState().boardsById)).toEqual(["VEC"]);
    expect(store.getState().activeBoardId).toBe("VEC");
    expect(view.result.current.error).toBeNull();
  });

  // AC5
  it("paints cached data first on a warm load, then revalidates in place", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());

    const fresh = [itemFixture(), itemFixture({ key: "VEC-3", rank: "n" })];
    // Held open so the in-flight state is observed rather than raced.
    const gate = createGate();
    const { store, view } = renderSync(cache, {
      ...snapshotRecording(fresh),
      gate: gate.promise,
    });

    // The cache read lands before the network answers, and the status says so:
    // there is already something on screen.
    await waitFor(() => expect(view.result.current.status).toBe("revalidating"));
    expect(Object.keys(store.getState().issuesById)).toEqual(["VEC-2"]);

    gate.release();

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(Object.keys(store.getState().issuesById)).toEqual(["VEC-2", "VEC-3"]);
  });

  it("leaves an active board that still exists alone, so no React key changes", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());

    const { store, view } = renderSync(cache, snapshotRecording());

    // Read after the cache lands, which is where the active board comes from.
    await waitFor(() => expect(store.getState().hydrated).toBe(true));
    const before = store.getState().activeBoardId;
    expect(before).toBe("VEC");

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(store.getState().activeBoardId).toBe(before);
  });

  it("moves to a surviving board when the cached one is gone from the server", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());
    localStorage.setItem("ui:activeBoardId", JSON.stringify("board-deleted"));

    const { store, view } = renderSync(cache, snapshotRecording());
    await waitFor(() => expect(view.result.current.status).toBe("ready"));

    expect(store.getState().activeBoardId).toBe("VEC");
  });

  // AC6
  it("keeps the cached board and reports staleness when revalidation fails", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());

    const { store, view } = renderSync(cache, {
      routes: {},
      networkError: new TypeError("Failed to fetch"),
    });

    await waitFor(() => expect(view.result.current.status).toBe("stale"));
    // The board is still there. A failed refresh does not blank it.
    expect(Object.keys(store.getState().boardsById)).toEqual(["VEC"]);
    expect(Object.keys(store.getState().issuesById)).toEqual(["VEC-2"]);
    expect(view.result.current.error?.kind).toBe("network");
    expect(view.result.current.canRetry).toBe(true);
  });

  // AC7
  it("reports a hard failure with no cache, offering a retry", async () => {
    const { store, view } = renderSync(new MemoryCache(), {
      routes: {},
      networkError: new TypeError("Failed to fetch"),
    });

    await waitFor(() => expect(view.result.current.status).toBe("error"));
    expect(Object.keys(store.getState().boardsById)).toHaveLength(0);
    expect(view.result.current.canRetry).toBe(true);
  });

  it("recovers on retry and clears the error", async () => {
    const store = createWorkspaceStore(new MemoryCache());
    const recorded = createRecordedTransport({
      routes: {},
      networkError: new TypeError("Failed to fetch"),
    });
    const view = renderHook(() =>
      useWorkspaceSync({ workspaceKey: "VEC", store, createClient: clientFactory(recorded) }),
    );

    await waitFor(() => expect(view.result.current.status).toBe("error"));

    recorded.play(snapshotRecording());
    act(() => view.result.current.retry());

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(view.result.current.error).toBeNull();
    expect(Object.keys(store.getState().boardsById)).toEqual(["VEC"]);
  });

  it("offers no retry for a misconfiguration, which repeating cannot fix", async () => {
    const store = createWorkspaceStore(new MemoryCache());
    const view = renderHook(() =>
      useWorkspaceSync({
        workspaceKey: "VEC",
        store,
        createClient: () => {
          throw readApiConfigError();
        },
      }),
    );

    await waitFor(() => expect(view.result.current.status).toBe("error"));
    expect(view.result.current.error?.kind).toBe("config");
    expect(view.result.current.canRetry).toBe(false);
  });

  it("offers no retry when the server answers in a shape the contract forbids", async () => {
    const { view } = renderSync(new MemoryCache(), {
      routes: {
        "/workspaces/VEC": { body: workspaceFixture },
        "/boards": { body: [{ ...boardFixture, columns: "none" }] },
        "/items": { body: itemPage([]) },
      },
    });

    await waitFor(() => expect(view.result.current.status).toBe("error"));
    expect(view.result.current.error?.kind).toBe("format");
    expect(view.result.current.canRetry).toBe(false);
  });
});

describe("useWorkspaceSync across workspaces", () => {
  function renderSwitchable(cache: AsyncCache, recording: Recording, workspaceKey: string | null) {
    const store = createWorkspaceStore(cache);
    const recorded = createRecordedTransport(recording);
    const view = renderHook(
      ({ key }: { key: string | null }) =>
        useWorkspaceSync({ workspaceKey: key, store, createClient: clientFactory(recorded) }),
      { initialProps: { key: workspaceKey } },
    );
    return { store, recorded, view };
  }

  it("fetches nothing while no workspace is chosen", async () => {
    const { recorded, view } = renderSwitchable(new MemoryCache(), twoWorkspacesRecording(), null);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(recorded.calls).toEqual([]);
    expect(view.result.current.status).toBe("loading");
  });

  it("starts the next workspace over at loading, then holds only its data", async () => {
    const { store, view } = renderSwitchable(new MemoryCache(), twoWorkspacesRecording(), "VEC");
    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(Object.keys(store.getState().boardsById)).toEqual(["VEC"]);

    view.rerender({ key: "OPS" });
    // From the very render that names it: not the previous workspace's "ready".
    expect(view.result.current.status).toBe("loading");

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(store.getState().workspace?.key).toBe("OPS");
    expect(Object.keys(store.getState().boardsById)).toEqual(["OPS"]);
    expect(Object.keys(store.getState().issuesById)).toEqual(["OPS-1"]);
  });

  it("does not paint a cached workspace under another one's name", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());

    const gate = createGate();
    const { store, view } = renderSwitchable(
      cache,
      { ...twoWorkspacesRecording(), gate: gate.promise },
      otherWorkspaceFixture.key,
    );

    await waitFor(() => expect(store.getState().hydrated).toBe(true));
    // The cache holds VEC, so OPS is a cold load: nothing to show yet.
    expect(view.result.current.status).toBe("loading");
    expect(Object.keys(store.getState().boardsById)).toEqual([]);

    gate.release();
    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(Object.keys(store.getState().boardsById)).toEqual(["OPS"]);
  });
});

/** The error `readApiConfig` throws for a timeout that is not one. */
function readApiConfigError(): unknown {
  try {
    readApiConfig({ VITE_VECTIS_API_TIMEOUT_MS: "soon" });
  } catch (error) {
    return error;
  }
  throw new Error("expected readApiConfig to reject an unusable timeout");
}
