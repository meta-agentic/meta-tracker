import { describe, expect, it } from "vitest";
import { flattenTree, selectFlatTree, type TreeLevel } from "./selectors";
import type { Board, Epic, ID, Issue } from "./types";

const board: Board = {
  id: "b1",
  key: "VEC",
  name: "Delivery",
  columns: [
    { id: "todo", name: "To Do", order: 0 },
    { id: "done", name: "Done", order: 1 },
  ],
};
const otherBoard: Board = { ...board, id: "b2", name: "Platform" };

function epic(id: ID, key: string): Epic {
  return { id, key, title: `Epic ${key}`, color: "var(--vec-accent)" };
}

function issue(id: ID, over: Partial<Issue> = {}): Issue {
  return {
    id,
    key: id.toUpperCase(),
    boardId: "b1",
    epicId: null,
    columnId: "todo",
    title: id,
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

function state(issues: Issue[], epics: Epic[]) {
  const boardIssueIds: Record<ID, ID[]> = {};
  for (const i of issues) (boardIssueIds[i.boardId] ??= []).push(i.id);
  return {
    issuesById: Object.fromEntries(issues.map((i) => [i.id, i])),
    epicsById: Object.fromEntries(epics.map((e) => [e.id, e])),
    boardsById: { b1: board, b2: otherBoard },
    boardIssueIds,
  };
}

const fixture = state(
  [
    issue("s2", { epicId: "e1", columnId: "done", order: 0 }),
    issue("s1", { epicId: "e1", columnId: "todo", order: 1 }),
    issue("s3", { epicId: "e2" }),
    issue("loose"),
    issue("elsewhere", { boardId: "b2", epicId: "e3" }),
  ],
  [epic("e2", "VEC-10"), epic("e1", "VEC-2"), epic("e3", "VEC-3"), epic("e4", "VEC-4")],
);

const ids = (rows: { id: ID }[]) => rows.map((row) => row.id);

describe("selectFlatTree", () => {
  it("shows only the roots when nothing is expanded: epics by key, then stories with no epic", () => {
    const rows = selectFlatTree(fixture, "b1", new Set());
    expect(ids(rows)).toEqual(["e1", "e4", "e2", "loose"]);
    expect(rows.every((row) => row.depth === 0 && row.parentId === null)).toBe(true);
    expect(rows.map((row) => row.kind)).toEqual(["epic", "epic", "epic", "issue"]);
  });

  it("nests an expanded epic's stories in board order: by column, then by order", () => {
    const rows = selectFlatTree(fixture, "b1", new Set(["e1"]));
    expect(ids(rows)).toEqual(["e1", "s1", "s2", "e4", "e2", "loose"]);
    expect(rows[1]).toMatchObject({ depth: 1, parentId: "e1", kind: "issue", hasChildren: false });
  });

  it("removes a collapsed parent's whole subtree", () => {
    const open = selectFlatTree(fixture, "b1", new Set(["e1", "e2"]));
    const closed = selectFlatTree(fixture, "b1", new Set(["e2"]));
    expect(ids(open)).toContain("s1");
    expect(ids(closed)).not.toContain("s1");
    expect(ids(closed)).not.toContain("s2");
    expect(ids(closed)).toContain("s3");
  });

  it("keeps an epic with no stories anywhere, at zero, and drops one whose stories are all on another board", () => {
    const rows = selectFlatTree(fixture, "b1", new Set());
    const empty = rows.find((row) => row.id === "e4");
    expect(empty).toMatchObject({ hasChildren: false, childCount: 0 });
    expect(ids(rows)).not.toContain("e3");
    // On the board that holds its story, it is there.
    expect(ids(selectFlatTree(fixture, "b2", new Set()))).toContain("e3");
  });

  it("numbers siblings for assistive technology", () => {
    const rows = selectFlatTree(fixture, "b1", new Set(["e1"]));
    expect(rows.map((row) => [row.id, row.position, row.siblings])).toEqual([
      ["e1", 1, 4],
      ["s1", 1, 2],
      ["s2", 2, 2],
      ["e4", 2, 4],
      ["e2", 3, 4],
      ["loose", 4, 4],
    ]);
  });

  it("is a pure function of its inputs", () => {
    const expanded = new Set(["e1"]);
    const before = JSON.stringify(fixture);
    expect(selectFlatTree(fixture, "b1", expanded)).toEqual(selectFlatTree(fixture, "b1", expanded));
    expect(JSON.stringify(fixture)).toBe(before);
    expect([...expanded]).toEqual(["e1"]);
  });
});

describe("flattenTree", () => {
  // A third level above the epic, as VEC-24 would add it: the traversal is
  // unchanged, only the level list grows.
  const children: Record<string, ID[]> = {
    root: ["i1", "i2"],
    i1: ["e1", "e2"],
    i2: [],
    e1: ["s1", "s2"],
    e2: ["s3"],
  };
  const levels: TreeLevel[] = [
    { kind: "initiative", childrenOf: (p) => (p === null ? children.root : []) },
    { kind: "epic", childrenOf: (p) => (p === null ? ["orphan-epic"] : (children[p] ?? [])) },
    { kind: "issue", childrenOf: (p) => (p === null ? [] : (children[p] ?? [])) },
  ];

  it("flattens three levels with the same traversal", () => {
    const rows = flattenTree(levels, new Set(["i1", "e1", "e2"]));
    expect(rows.map((row) => [row.id, row.kind, row.depth, row.parentId])).toEqual([
      ["i1", "initiative", 0, null],
      ["e1", "epic", 1, "i1"],
      ["s1", "issue", 2, "e1"],
      ["s2", "issue", 2, "e1"],
      ["e2", "epic", 1, "i1"],
      ["s3", "issue", 2, "e2"],
      ["i2", "initiative", 0, null],
      ["orphan-epic", "epic", 0, null],
    ]);
    expect(rows.find((row) => row.id === "i2")).toMatchObject({ hasChildren: false, childCount: 0 });
  });

  it("hides every descendant of a collapsed row, however deep", () => {
    const rows = flattenTree(levels, new Set(["e1", "e2"]));
    expect(rows.map((row) => row.id)).toEqual(["i1", "i2", "orphan-epic"]);
  });
});
