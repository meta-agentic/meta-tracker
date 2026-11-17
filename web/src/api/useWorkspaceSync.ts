import { useCallback, useEffect, useRef, useState } from "react";
import type { StoreApi } from "zustand/vanilla";
import { workspaceStore, type WorkspaceState } from "../store/workspaceStore";
import { fetchWorkspaceSnapshot, type WorkspaceApiClient } from "./client";
import { ApiError, isRetryable } from "./errors";

/**
 * Hydrates the store from the server, cache first.
 *
 * The order is the whole point of R-UX-5. The cache is read before the network
 * is touched, so a returning session paints its boards from IndexedDB on the
 * first frame and the server response replaces the data underneath a board that
 * is already on screen. The active board id is left alone whenever it still
 * names a board, so revalidation changes no React key and remounts nothing —
 * `App` keys `BoardView` on that id precisely so a board swap remounts and a
 * data refresh does not.
 *
 * The four states the synthetic path never needed are distinguished because the
 * UI owes a different thing in each: nothing to show yet, something stale to
 * keep showing, something fresh, or nothing at all.
 *
 * A switch to another workspace starts over at "loading" from the first render
 * that names it: the store still holds the workspace left behind until the
 * cache read for the new one lands, and none of it may show under the new name.
 */
export type SyncStatus =
  /** No data yet and a request in flight. The board must not render empty. */
  | "loading"
  /** Cached data on screen, a request in flight behind it. */
  | "revalidating"
  /** Server data on screen. */
  | "ready"
  /** Cached data on screen; revalidation failed. Keep it, say so, do not blank. */
  | "stale"
  /** No data and no way to get it. */
  | "error";

export interface WorkspaceSync {
  status: SyncStatus;
  /** The most recent failure, retained while `status` is "stale" or "error". */
  error: ApiError | null;
  /** False for a failure that repeating cannot fix, so no dead retry is offered. */
  canRetry: boolean;
  retry: () => void;
}

export interface WorkspaceSyncOptions {
  /** The workspace to load. Null while none is chosen: nothing is fetched. */
  workspaceKey: string | null;
  /**
   * Builds the client for a workspace. Called on every attempt so a
   * configuration error surfaces through the same path as a network one. The
   * suite injects one over a recorded transport.
   */
  createClient: (workspaceKey: string) => WorkspaceApiClient;
  store?: StoreApi<WorkspaceState>;
}

function asApiError(caught: unknown): ApiError {
  if (caught instanceof ApiError) return caught;
  return new ApiError("network", "the workspace could not be loaded", {
    cause: caught,
  });
}

/** What the last attempt came to, and for which workspace. */
interface Outcome {
  workspaceKey: string | null;
  status: SyncStatus;
  error: ApiError | null;
}

export function useWorkspaceSync(options: WorkspaceSyncOptions): WorkspaceSync {
  const store = options.store ?? workspaceStore;
  const { workspaceKey } = options;
  const [outcome, setOutcome] = useState<Outcome>({ workspaceKey, status: "loading", error: null });
  const [attempt, setAttempt] = useState(0);

  // Held in a ref so an inline `createClient` in a caller's render does not
  // re-trigger the fetch on every render. Written in an effect rather than
  // during render, and declared before the fetch effect so it is already
  // current when that one first runs.
  const createClientRef = useRef(options.createClient);
  useEffect(() => {
    createClientRef.current = options.createClient;
  });

  useEffect(() => {
    if (workspaceKey === null) return;
    const controller = new AbortController();
    let cancelled = false;
    // An attempt in flight keeps the last failure until it settles itself.
    const setStatus = (status: SyncStatus) =>
      setOutcome((before) => ({
        workspaceKey,
        status,
        error: before.workspaceKey === workspaceKey ? before.error : null,
      }));

    const run = async () => {
      const initial = store.getState();
      // The cache read comes first and unconditionally: painting from it is what
      // makes a warm load instant, and its result decides whether a later
      // failure is recoverable or fatal. After a switch the store still holds
      // the workspace left behind, so it is read again for this one.
      if (!initial.hydrated || initial.workspace?.key !== workspaceKey) {
        await initial.hydrate(workspaceKey);
      }
      if (cancelled) return;

      const held = store.getState();
      const cached =
        held.workspace?.key === workspaceKey && Object.keys(held.boardsById).length > 0;
      setStatus(cached ? "revalidating" : "loading");

      try {
        const client = createClientRef.current(workspaceKey);
        const snapshot = await fetchWorkspaceSnapshot(client, controller.signal);
        if (cancelled) return;

        store.getState().ingestSnapshot(snapshot);

        const after = store.getState();
        const activeSurvives =
          after.activeBoardId !== null && after.boardsById[after.activeBoardId] !== undefined;
        // Reassigning an active board that still exists would change BoardView's
        // key and throw away the scroll position on every revalidation.
        if (!activeSurvives) after.setActiveBoard(snapshot.boards[0]?.id ?? null);

        setOutcome({ workspaceKey, status: "ready", error: null });
      } catch (caught) {
        if (cancelled || controller.signal.aborted) return;
        setOutcome({ workspaceKey, status: cached ? "stale" : "error", error: asApiError(caught) });
      }
    };

    void run();

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [store, attempt, workspaceKey]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  // Until the attempt for a newly named workspace reports, the outcome held is
  // the previous workspace's.
  const { status, error } =
    outcome.workspaceKey === workspaceKey ? outcome : { status: "loading" as const, error: null };
  return {
    status,
    error,
    canRetry: error !== null && isRetryable(error),
    retry,
  };
}
