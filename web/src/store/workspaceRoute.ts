// SPDX-License-Identifier: Apache-2.0
import { useCallback, useMemo, useSyncExternalStore } from "react";
import { EMPTY_FILTER, writeFilter } from "./boardFilter";

/**
 * Which workspace the page shows, and the rule that picks it.
 *
 * The workspace lives in the URL (`?ws=KEY`), not in the build, so one bundle
 * serves any deployment, a link opens the workspace it was copied from, and the
 * back button returns to the one before. A switch pushes a history entry; the
 * board filter only ever replaces the entry it is on, so going back restores the
 * previous workspace together with its filter.
 */

export const WORKSPACE_PARAM = "ws";

/** The workspace key format enforced by `io.vectis.domain.Workspace`. */
const WORKSPACE_KEY_FORMAT = /^[A-Z][A-Z0-9]{1,9}$/;

/** The URL is untrusted: this much of a value is enough to name it back to the user. */
const MAX_PARAM = 40;

/** True for a key the server could hold; anything else names no workspace. */
export function isWorkspaceKey(value: string): boolean {
  return WORKSPACE_KEY_FORMAT.test(value);
}

/** The workspace the URL names, or null when it names none. */
export function readWorkspaceParam(search: string): string | null {
  let value: string | null;
  try {
    value = new URLSearchParams(search).get(WORKSPACE_PARAM);
  } catch {
    return null;
  }
  const key = (value ?? "").trim().slice(0, MAX_PARAM);
  return key === "" ? null : key;
}

/**
 * The query string that opens `key`, from the current one. The board filter is
 * dropped, because its epics and labels belong to the workspace being left;
 * every other parameter is kept.
 */
export function workspaceSearch(search: string, key: string): string {
  const params = new URLSearchParams(writeFilter(search, EMPTY_FILTER));
  params.set(WORKSPACE_PARAM, key);
  return `?${params}`;
}

/** The link that opens `key` from the page as it is now. */
export function workspaceHref(key: string): string {
  const { pathname, search, hash } = window.location;
  return `${pathname}${workspaceSearch(search, key)}${hash}`;
}

export type WorkspaceChoice =
  | { kind: "open"; key: string }
  /** Show the picker. `missing` is the key asked for when the server has no such workspace. */
  | { kind: "pick"; missing: string | null }
  /** Nothing names a workspace, and the list that decides has not arrived. */
  | { kind: "wait" };

export interface WorkspaceChoiceInput {
  /** From the URL. */
  requested: string | null;
  /** The build-time default, for when the URL names none. */
  fallback: string | null;
  /** The server's workspaces; null until they are listed. */
  listed: readonly { key: string }[] | null;
}

/**
 * The URL's workspace, else the build-time default, else the only workspace
 * there is, else the picker.
 *
 * A named workspace opens before the list arrives, so a returning session paints
 * from its cache at once instead of waiting a round trip to be told what it
 * already knows. Once the list is in, a name it lacks is reported as not found
 * beside the picker; a name no workspace can have is reported without asking.
 */
export function selectWorkspace({ requested, fallback, listed }: WorkspaceChoiceInput): WorkspaceChoice {
  const named = requested ?? fallback;
  if (named !== null) {
    if (!isWorkspaceKey(named)) return { kind: "pick", missing: named };
    if (listed === null || listed.some((workspace) => workspace.key === named)) {
      return { kind: "open", key: named };
    }
    return { kind: "pick", missing: named };
  }
  if (listed === null) return { kind: "wait" };
  if (listed.length === 1) return { kind: "open", key: listed[0].key };
  return { kind: "pick", missing: null };
}

function subscribe(onChange: () => void) {
  window.addEventListener("popstate", onChange);
  return () => window.removeEventListener("popstate", onChange);
}

const currentSearch = () => window.location.search;

/** The workspace the URL names, and the switch that names another in a new history entry. */
export function useWorkspaceParam(): [string | null, (key: string) => void] {
  const search = useSyncExternalStore(subscribe, currentSearch, () => "");
  const requested = useMemo(() => readWorkspaceParam(search), [search]);
  const open = useCallback((key: string) => {
    const url = workspaceHref(key);
    try {
      window.history.pushState(null, "", url);
    } catch {
      // Safari throws after too many history writes in a short while. A full
      // navigation to the same URL still gets the user there.
      window.location.assign(url);
      return;
    }
    // Every reader of the URL, the board filter included, already follows
    // popstate as "the URL changed under me", which is what a switch is.
    window.dispatchEvent(new PopStateEvent("popstate", { state: null }));
  }, []);
  return [requested, open];
}
