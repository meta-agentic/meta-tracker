import { useCallback, useEffect, useRef, useState } from "react";
import { defaultCreateClient } from "./useWorkspaceSync";
import type { WorkspaceApiClient } from "./client";
import { ApiError } from "./errors";
import type { WireSprint } from "./wire";

/**
 * The sprints of the workspace, newest first, and the item keys of the one open.
 *
 * The server lists them oldest first. Sprint keys are numbered as sprints are
 * created, and some sprints have no dates yet (a planned one), so the number in
 * the key orders them (S10 after S2, which a text sort gets wrong) and the start
 * date only breaks a tie. The order is computed here, not taken from the array's
 * position, so a server that changes its own order does not flip the switcher.
 */

function keyNumber(key: string): number {
  const match = /(\d+)$/.exec(key);
  return match ? Number(match[1]) : 0;
}

function oldestFirst(a: WireSprint, b: WireSprint): number {
  return (
    keyNumber(a.key) - keyNumber(b.key) ||
    (a.start ?? "").localeCompare(b.start ?? "") ||
    a.key.localeCompare(b.key)
  );
}

export function newestFirst(sprints: readonly WireSprint[]): WireSprint[] {
  return [...sprints].sort((a, b) => oldestFirst(b, a));
}

export type LoadStatus = "loading" | "ready" | "error";

export interface SprintsState {
  status: LoadStatus;
  /** Newest first. */
  sprints: WireSprint[];
  /** The sprint asked for if it exists, else the active one, else none. */
  selected: WireSprint | null;
  error: ApiError | null;
  /** Status of the item keys of the selected sprint; "ready" with none selected. */
  itemsStatus: LoadStatus;
  /** Keys of the items in the selected sprint, null until they arrive. */
  itemKeys: ReadonlySet<string> | null;
  retry: () => void;
}

function asApiError(caught: unknown): ApiError {
  if (caught instanceof ApiError) return caught;
  return new ApiError("network", "the sprints could not be loaded", { cause: caught });
}

export function useSprints(
  chosenKey: string | null,
  createClient?: () => WorkspaceApiClient,
): SprintsState {
  const [sprints, setSprints] = useState<WireSprint[]>([]);
  const [status, setStatus] = useState<LoadStatus>("loading");
  const [error, setError] = useState<ApiError | null>(null);
  const [loaded, setLoaded] = useState<{ key: string; keys: ReadonlySet<string> } | null>(null);
  const [itemsError, setItemsError] = useState<{ key: string; error: ApiError } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const selected =
    sprints.find((s) => s.key === chosenKey) ?? sprints.find((s) => s.state === "ACTIVE") ?? null;
  const sprintKey = selected?.key ?? null;

  // A client per attempt, as the workspace sync builds one: a configuration
  // error then surfaces through the same path as a network one.
  const createRef = useRef(createClient);
  useEffect(() => {
    createRef.current = createClient;
  });

  useEffect(() => {
    const controller = new AbortController();
    (async () => {
      setStatus("loading");
      try {
        const client = (createRef.current ?? defaultCreateClient)();
        const listed = await client.listSprints(controller.signal);
        setSprints(newestFirst(listed));
        setError(null);
        setStatus("ready");
      } catch (caught) {
        if (controller.signal.aborted) return;
        setError(asApiError(caught));
        setStatus("error");
      }
    })();
    return () => controller.abort();
  }, [attempt]);

  useEffect(() => {
    if (sprintKey === null) return;
    const controller = new AbortController();
    (async () => {
      try {
        const client = (createRef.current ?? defaultCreateClient)();
        const keys = await client.listSprintItemKeys(sprintKey, controller.signal);
        setLoaded({ key: sprintKey, keys: new Set(keys) });
        setItemsError(null);
      } catch (caught) {
        if (controller.signal.aborted) return;
        setItemsError({ key: sprintKey, error: asApiError(caught) });
      }
    })();
    return () => controller.abort();
  }, [sprintKey, attempt]);

  const current = sprintKey !== null && loaded?.key === sprintKey ? loaded.keys : null;
  const failed = sprintKey !== null && itemsError?.key === sprintKey ? itemsError.error : null;
  return {
    status,
    sprints,
    selected,
    error: error ?? failed,
    itemsStatus: sprintKey === null || current ? "ready" : failed ? "error" : "loading",
    itemKeys: current,
    retry: useCallback(() => {
      setItemsError(null);
      setAttempt((n) => n + 1);
    }, []),
  };
}
