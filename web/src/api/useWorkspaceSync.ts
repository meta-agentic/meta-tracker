import { useCallback, useEffect, useRef, useState } from "react";
import type { StoreApi } from "zustand/vanilla";
import { workspaceStore, type WorkspaceState } from "../store/workspaceStore";
import {
  createWorkspaceApiClientFromEnv,
  fetchWorkspaceSnapshot,
  type WorkspaceApiClient,
} from "./client";
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
  /**
   * Builds the client. Called on every attempt so a configuration error
   * surfaces through the same path as a network one. The suite injects a client
   * over a recorded transport; production omits it and reads the environment.
   */
  createClient?: () => WorkspaceApiClient;
  store?: StoreApi<WorkspaceState>;
}

function defaultCreateClient(): WorkspaceApiClient {
  return createWorkspaceApiClientFromEnv(import.meta.env);
}

function asApiError(caught: unknown): ApiError {
  if (caught instanceof ApiError) return caught;
  return new ApiError("network", "the workspace could not be loaded", {
    cause: caught,
  });
}

export function useWorkspaceSync(options: WorkspaceSyncOptions = {}): WorkspaceSync {
  const store = options.store ?? workspaceStore;
  const [status, setStatus] = useState<SyncStatus>("loading");
  const [error, setError] = useState<ApiError | null>(null);
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
    const controller = new AbortController();
    let cancelled = false;

    const run = async () => {
      const initial = store.getState();
      // The cache read comes first and unconditionally: painting from it is what
      // makes a warm load instant, and its result decides whether a later
      // failure is recoverable or fatal.
      if (!initial.hydrated) await initial.hydrate();
      if (cancelled) return;

      const cached = Object.keys(store.getState().boardsById).length > 0;
      setStatus(cached ? "revalidating" : "loading");

      try {
        const client = (createClientRef.current ?? defaultCreateClient)();
        const snapshot = await fetchWorkspaceSnapshot(client, controller.signal);
        if (cancelled) return;

        store.getState().ingestSnapshot(snapshot);

        const after = store.getState();
        const activeSurvives =
          after.activeBoardId !== null && after.boardsById[after.activeBoardId] !== undefined;
        // Reassigning an active board that still exists would change BoardView's
        // key and throw away the scroll position on every revalidation.
        if (!activeSurvives) after.setActiveBoard(snapshot.boards[0]?.id ?? null);

        setError(null);
        setStatus("ready");
      } catch (caught) {
        if (cancelled || controller.signal.aborted) return;
        setError(asApiError(caught));
        setStatus(cached ? "stale" : "error");
      }
    };

    void run();

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [store, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);

  return {
    status,
    error,
    canRetry: error !== null && isRetryable(error),
    retry,
  };
}
