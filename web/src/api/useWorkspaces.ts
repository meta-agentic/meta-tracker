// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useState } from "react";
import { createApiClientFromEnv, type ApiClient } from "./client";
import { ApiError, isRetryable } from "./errors";
import type { WireWorkspace } from "./wire";

export function defaultCreateApi(): ApiClient {
  return createApiClientFromEnv(import.meta.env);
}

/**
 * The client, or why there is none. Only the build-time configuration can fail
 * here, and it fails the same way however often it is asked, so it is asked once.
 */
export type ApiConnection = { api: ApiClient; error: null } | { api: null; error: ApiError };

export function connectApi(createApi: () => ApiClient): ApiConnection {
  try {
    return { api: createApi(), error: null };
  } catch (caught) {
    const error =
      caught instanceof ApiError
        ? caught
        : new ApiError("config", "the API client could not be created", { cause: caught });
    return { api: null, error };
  }
}

export interface WorkspaceList {
  status: "loading" | "ready" | "error";
  /** By name, then key; null until listed. */
  workspaces: WireWorkspace[] | null;
  error: ApiError | null;
  /** False for a failure that repeating cannot fix, so no dead retry is offered. */
  canRetry: boolean;
  retry: () => void;
}

/** A person scans the list for a name, so it is in name order, whatever order the server sends. */
function byName(a: WireWorkspace, b: WireWorkspace): number {
  return a.name.localeCompare(b.name) || a.key.localeCompare(b.key);
}

/** The server's workspaces, for the switcher and the picker. */
export function useWorkspaces(connection: ApiConnection): WorkspaceList {
  const [workspaces, setWorkspaces] = useState<WireWorkspace[] | null>(null);
  const [failure, setFailure] = useState<ApiError | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const { api } = connection;
    if (api === null) return;
    const controller = new AbortController();
    (async () => {
      try {
        const listed = await api.listWorkspaces(controller.signal);
        setWorkspaces([...listed].sort(byName));
        setFailure(null);
      } catch (caught) {
        if (controller.signal.aborted) return;
        setFailure(
          caught instanceof ApiError
            ? caught
            : new ApiError("network", "the workspaces could not be listed", { cause: caught }),
        );
      }
    })();
    return () => controller.abort();
  }, [connection, attempt]);

  const error = connection.error ?? failure;
  return {
    status: error !== null ? "error" : workspaces === null ? "loading" : "ready",
    workspaces: error !== null ? null : workspaces,
    error,
    canRetry: error !== null && isRetryable(error),
    retry: useCallback(() => {
      setFailure(null);
      setAttempt((n) => n + 1);
    }, []),
  };
}
