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
  snapshotRecording,
  workspaceFixture,
  type Recording,
} from "../test/recordedTransport";

const config = readApiConfig({ VITE_VECTIS_WORKSPACE_KEY: "VEC" });

function clientFactory(recorded: ReturnType<typeof createRecordedTransport>) {
  return () => createWorkspaceApiClient({ config, transport: recorded.transport });
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
    useWorkspaceSync({ store, createClient: clientFactory(recorded) }),
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
    expect(Object.keys(store.getState().boardsById)).toEqual(["board-1"]);
    expect(store.getState().activeBoardId).toBe("board-1");
    expect(view.result.current.error).toBeNull();
  });

  // AC5
  it("paints cached data first on a warm load, then revalidates in place", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());

    const fresh = [itemFixture(), itemFixture({ id: "item-2", key: "VEC-3", rank: "n" })];
    // Held open so the in-flight state is observed rather than raced.
    const gate = createGate();
    const { store, view } = renderSync(cache, {
      ...snapshotRecording(fresh),
      gate: gate.promise,
    });

    // The cache read lands before the network answers, and the status says so:
    // there is already something on screen.
    await waitFor(() => expect(view.result.current.status).toBe("revalidating"));
    expect(Object.keys(store.getState().issuesById)).toEqual(["item-1"]);

    gate.release();

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(Object.keys(store.getState().issuesById)).toEqual(["item-1", "item-2"]);
  });

  it("leaves an active board that still exists alone, so no React key changes", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());

    const { store, view } = renderSync(cache, snapshotRecording());

    // Read after the cache lands, which is where the active board comes from.
    await waitFor(() => expect(store.getState().hydrated).toBe(true));
    const before = store.getState().activeBoardId;
    expect(before).toBe("board-1");

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(store.getState().activeBoardId).toBe(before);
  });

  it("moves to a surviving board when the cached one is gone from the server", async () => {
    const cache = new MemoryCache();
    await warmCache(cache, snapshotOf());
    localStorage.setItem("ui:activeBoardId", JSON.stringify("board-deleted"));

    const { store, view } = renderSync(cache, snapshotRecording());
    await waitFor(() => expect(view.result.current.status).toBe("ready"));

    expect(store.getState().activeBoardId).toBe("board-1");
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
    expect(Object.keys(store.getState().boardsById)).toEqual(["board-1"]);
    expect(Object.keys(store.getState().issuesById)).toEqual(["item-1"]);
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
      useWorkspaceSync({ store, createClient: clientFactory(recorded) }),
    );

    await waitFor(() => expect(view.result.current.status).toBe("error"));

    recorded.play(snapshotRecording());
    act(() => view.result.current.retry());

    await waitFor(() => expect(view.result.current.status).toBe("ready"));
    expect(view.result.current.error).toBeNull();
    expect(Object.keys(store.getState().boardsById)).toEqual(["board-1"]);
  });

  it("offers no retry for a misconfiguration, which repeating cannot fix", async () => {
    const store = createWorkspaceStore(new MemoryCache());
    const view = renderHook(() =>
      useWorkspaceSync({
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
        "/items": { body: [] },
      },
    });

    await waitFor(() => expect(view.result.current.status).toBe("error"));
    expect(view.result.current.error?.kind).toBe("format");
    expect(view.result.current.canRetry).toBe(false);
  });
});

/** The error `readApiConfig` throws when the environment names no workspace. */
function readApiConfigError(): unknown {
  try {
    readApiConfig({});
  } catch (error) {
    return error;
  }
  throw new Error("expected readApiConfig to reject an empty environment");
}
