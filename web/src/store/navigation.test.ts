import { beforeEach, describe, expect, it } from "vitest";
import { createWorkspaceStore } from "./workspaceStore";
import { MemoryCache } from "./cache";
import { ACTIVE_TAB_KEY, TREE_EXPANDED_KEY } from "./navigation";

beforeEach(() => {
  localStorage.clear();
});

describe("navigation slice", () => {
  it("survives a reload: a new store reads the tab and the expansion set back", () => {
    const first = createWorkspaceStore(new MemoryCache());
    first.getState().setActiveTab("backlog");
    first.getState().toggleTreeRow("epic-1");
    first.getState().toggleTreeRow("epic-2");
    first.getState().toggleTreeRow("epic-1");

    // What a page reload does: the module-level store is built again from storage.
    const reloaded = createWorkspaceStore(new MemoryCache()).getState();
    expect(reloaded.activeTab).toBe("backlog");
    expect(reloaded.treeExpanded).toEqual(["epic-2"]);
  });

  it("falls back to the defaults when the persisted values are unusable", () => {
    localStorage.setItem(ACTIVE_TAB_KEY, JSON.stringify("settings"));
    localStorage.setItem(TREE_EXPANDED_KEY, JSON.stringify([1, 2]));

    const state = createWorkspaceStore(new MemoryCache()).getState();
    expect(state.activeTab).toBe("boards");
    expect(state.treeExpanded).toEqual([]);
  });

  it("de-duplicates a bulk expansion", () => {
    const store = createWorkspaceStore(new MemoryCache());
    store.getState().setTreeExpanded(["a", "b", "a"]);
    expect(store.getState().treeExpanded).toEqual(["a", "b"]);
  });
});
