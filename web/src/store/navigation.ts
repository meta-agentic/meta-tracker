import { uiTracking } from "./cache";
import type { ID } from "./types";

/**
 * Navigation slice: which tab is open and which backlog-tree rows are expanded.
 *
 * Both are small, read on every interaction and wanted on the first render, so
 * they ride on the store's synchronous tier (`uiTracking` → localStorage), the
 * same tier `activeBoardId` and the preferences already use — not the async
 * IndexedDB tier that holds board data.
 */

export const ACTIVE_TAB_KEY = "ui:activeTab";
export const TREE_EXPANDED_KEY = "ui:treeExpanded";

export const APP_TABS = ["boards", "backlog", "timeline"] as const;

export type AppTab = (typeof APP_TABS)[number];

function isAppTab(value: unknown): value is AppTab {
  return typeof value === "string" && (APP_TABS as readonly string[]).includes(value);
}

function isIdList(value: unknown): value is ID[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === "string");
}

export interface NavigationState {
  activeTab: AppTab;
  /**
   * Expanded tree rows, by id. Ids are unique across levels, so one list serves
   * every board. An id that no longer names a row expands nothing.
   */
  treeExpanded: ID[];
  setActiveTab: (tab: AppTab) => void;
  toggleTreeRow: (id: ID) => void;
  setTreeExpanded: (ids: ID[]) => void;
  /**
   * Drops expanded ids `keep` rejects. Called when a snapshot lands, so ids of
   * rows that no longer exist do not pile up in storage.
   */
  retainTreeRows: (keep: (id: ID) => boolean) => void;
}

/** Reads the persisted values, dropping anything an older build or a hand edit left behind. */
export function readPersistedNavigation(): Pick<NavigationState, "activeTab" | "treeExpanded"> {
  const tab = uiTracking.read<unknown>(ACTIVE_TAB_KEY, "boards");
  const expanded = uiTracking.read<unknown>(TREE_EXPANDED_KEY, []);
  return {
    activeTab: isAppTab(tab) ? tab : "boards",
    treeExpanded: isIdList(expanded) ? expanded : [],
  };
}

export function createNavigationSlice(
  set: (partial: Partial<NavigationState>) => void,
  get: () => NavigationState,
): NavigationState {
  const writeExpanded = (ids: ID[]) => {
    set({ treeExpanded: ids });
    uiTracking.write(TREE_EXPANDED_KEY, ids);
  };

  return {
    ...readPersistedNavigation(),

    setActiveTab: (tab) => {
      set({ activeTab: tab });
      uiTracking.write(ACTIVE_TAB_KEY, tab);
    },

    toggleTreeRow: (id) => {
      const current = get().treeExpanded;
      writeExpanded(
        current.includes(id) ? current.filter((entry) => entry !== id) : [...current, id],
      );
    },

    setTreeExpanded: (ids) => writeExpanded([...new Set(ids)]),

    retainTreeRows: (keep) => {
      const current = get().treeExpanded;
      const kept = current.filter(keep);
      if (kept.length !== current.length) writeExpanded(kept);
    },
  };
}
