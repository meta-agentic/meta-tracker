import { beforeEach, describe, expect, it } from "vitest";
import { createWorkspaceStore } from "./workspaceStore";
import { IndexedDbCache, MemoryCache } from "./cache";
import {
  selectBoardColumns,
  selectBoardIssues,
  selectBoardList,
} from "./selectors";
import { generateWorkspace } from "../lib/synthetic";
import type { Issue } from "./types";

function issue(over: Partial<Issue>): Issue {
  return {
    id: "i1",
    key: "PROJ-1",
    boardId: "board-0",
    epicId: null,
    columnId: "col-todo",
    title: "t",
    order: 0,
    startDate: null,
    dueDate: null,
    storyPoints: null,
    ...over,
  };
}

beforeEach(() => {
  localStorage.clear();
});

describe("normalization + selectors", () => {
  it("indexes a snapshot into flat maps and slices a board without scanning", () => {
    const store = createWorkspaceStore(new MemoryCache());
    store.getState().ingestSnapshot(
      generateWorkspace({ boards: 3, epics: 6, issues: 300, seed: 7 }),
    );
    const state = store.getState();

    expect(Object.keys(state.boardsById)).toHaveLength(3);
    expect(Object.keys(state.issuesById)).toHaveLength(300);
    expect(selectBoardList(state)).toHaveLength(3);

    const board0 = selectBoardIssues(state, "board-0");
    expect(board0.every((i) => i.boardId === "board-0")).toBe(true);
    // index count matches the reconstructed slice
    expect(board0).toHaveLength(state.boardIssueIds["board-0"].length);
  });

  it("groups a board's issues by column, each column ordered", () => {
    const store = createWorkspaceStore(new MemoryCache());
    store.getState().ingestSnapshot({
      boards: [
        {
          id: "board-0",
          key: "B1",
          name: "B",
          columns: [{ id: "col-todo", name: "To Do", order: 0 }],
        },
      ],
      epics: [],
      issues: [
        issue({ id: "a", order: 2 }),
        issue({ id: "b", order: 0 }),
        issue({ id: "c", order: 1 }),
      ],
    });
    const columns = selectBoardColumns(store.getState(), "board-0");
    expect(columns["col-todo"].map((i) => i.id)).toEqual(["b", "c", "a"]);
  });
});

describe("upsertIssue", () => {
  it("re-indexes when an issue changes board", () => {
    const store = createWorkspaceStore(new MemoryCache());
    store.getState().ingestSnapshot({
      boards: [],
      epics: [],
      issues: [issue({ id: "a", boardId: "board-0" })],
    });
    store.getState().upsertIssue(issue({ id: "a", boardId: "board-1" }));
    const state = store.getState();
    expect(state.boardIssueIds["board-0"]).not.toContain("a");
    expect(state.boardIssueIds["board-1"]).toContain("a");
  });
});

describe("cache layering", () => {
  it("persists to the cache and re-hydrates a fresh store", async () => {
    const cache = new MemoryCache();
    const first = createWorkspaceStore(cache);
    first.getState().ingestSnapshot(
      generateWorkspace({ boards: 2, epics: 4, issues: 120, seed: 3 }),
    );
    first.getState().setActiveBoard("board-1");

    await new Promise((r) => setTimeout(r, 300)); // let debounced persist flush

    const second = createWorkspaceStore(cache);
    await second.getState().hydrate();
    const state = second.getState();

    expect(state.hydrated).toBe(true);
    expect(Object.keys(state.issuesById)).toHaveLength(120);
    expect(state.activeBoardId).toBe("board-1"); // UI tracking from localStorage
  });

  it("round-trips through a real IndexedDB adapter", async () => {
    const cache = new IndexedDbCache();
    await cache.set("k", { hello: "world" });
    expect(await cache.get<{ hello: string }>("k")).toEqual({ hello: "world" });
  });
});
