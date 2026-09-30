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
    type: null,
    labels: [],
    priority: null,
    description: null,
    dependsOn: [],
    relates: [],
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

describe("ids named after Object.prototype members", () => {
  const protoBoard = {
    id: "constructor",
    key: "P",
    name: "Proto",
    columns: [
      { id: "constructor", name: "A", order: 0 },
      { id: "__proto__", name: "B", order: 1 },
      { id: "toString", name: "C", order: 2 },
    ],
  };
  const protoSnapshot = () => ({
    boards: [protoBoard],
    epics: [{ id: "__proto__", key: "E-1", title: "Epic", color: "red" }],
    issues: [issue({ id: "__proto__", boardId: "constructor", columnId: "__proto__", epicId: "__proto__" })],
  });

  it("indexes and groups them as plain keys", () => {
    const store = createWorkspaceStore(new MemoryCache());
    store.getState().ingestSnapshot(protoSnapshot());
    const state = store.getState();

    expect(Object.keys(state.issuesById)).toEqual(["__proto__"]);
    expect(state.issuesById["__proto__"].columnId).toBe("__proto__");
    expect(state.epicsById["__proto__"].key).toBe("E-1");
    expect(state.boardsById["constructor"].name).toBe("Proto");
    // A missing id finds nothing, not an Object.prototype member.
    expect(state.issuesById["constructor"]).toBeUndefined();
    expect(state.boardsById["toString"]).toBeUndefined();
    expect(state.boardIssueIds["valueOf"]).toBeUndefined();

    const columns = selectBoardColumns(state, "constructor");
    expect(columns["__proto__"].map((i) => i.id)).toEqual(["__proto__"]);
    expect(columns["constructor"]).toBeUndefined();
    expect(columns["toString"]).toBeUndefined();
  });

  it("keeps them plain keys through an upsert, a move and a reload", async () => {
    const cache = new MemoryCache();
    const first = createWorkspaceStore(cache);
    first.getState().ingestSnapshot(protoSnapshot());
    first.getState().upsertIssue(issue({ id: "constructor", boardId: "toString" }));
    first.getState().moveIssue("__proto__", "toString", 3);
    await new Promise((r) => setTimeout(r, 300)); // let debounced persist flush

    const second = createWorkspaceStore(cache);
    await second.getState().hydrate();
    for (const state of [first.getState(), second.getState()]) {
      expect(state.issuesById["__proto__"].columnId).toBe("toString");
      expect(state.issuesById["constructor"].boardId).toBe("toString");
      expect(state.boardIssueIds["toString"]).toEqual(["constructor"]);
      expect(state.issuesById["valueOf"]).toBeUndefined();
      expect(state.boardIssueIds["hasOwnProperty"]).toBeUndefined();
    }
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
