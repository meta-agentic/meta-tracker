import { describe, expect, it } from "vitest";
import type { Board, Epic, Issue } from "../store/types";
import { ancestorChain, linkGroups, nodeOf, type ItemIndex } from "./itemTree";

const board: Board = {
  id: "b",
  key: "b",
  name: "B",
  columns: [{ id: "todo", name: "To do", order: 0 }],
};

function epic(key: string, over: Partial<Epic> = {}): Epic {
  return { id: key, key, title: `Epic ${key}`, color: "#000", boardId: "b", columnId: "todo", ...over };
}

function issue(key: string, over: Partial<Issue> = {}): Issue {
  return {
    id: key, key, boardId: "b", epicId: null, columnId: "todo", title: `Story ${key}`, order: 0,
    startDate: null, dueDate: null, storyPoints: null, type: "story", labels: [], priority: null,
    description: null, dependsOn: [], relates: [], ...over,
  };
}

function index(epics: Epic[], issues: Issue[]): ItemIndex {
  return {
    epicsById: Object.fromEntries(epics.map((e) => [e.id, e])),
    issuesById: Object.fromEntries(issues.map((i) => [i.id, i])),
    boardsById: { b: board },
  };
}

describe("ancestorChain", () => {
  it("lists an epic and the epic's own epic, outermost first", () => {
    const idx = index([epic("E1"), epic("E2", { epicId: "E1" })], [issue("S1", { epicId: "E2" })]);
    expect(ancestorChain(idx, "S1").map((n) => n.key)).toEqual(["E1", "E2"]);
    expect(ancestorChain(idx, "E1")).toEqual([]);
  });

  it("stops at a cycle and at a self-parent", () => {
    const idx = index(
      [epic("E1", { epicId: "E2" }), epic("E2", { epicId: "E1" }), epic("E3", { epicId: "E3" })],
      [issue("S1", { epicId: "E1" }), issue("S3", { epicId: "E3" })],
    );
    expect(ancestorChain(idx, "S1").map((n) => n.key)).toEqual(["E2", "E1"]);
    expect(ancestorChain(idx, "S3").map((n) => n.key)).toEqual(["E3"]);
    expect(ancestorChain(idx, "E1").map((n) => n.key)).toEqual(["E2"]);
  });
});

describe("linkGroups", () => {
  it("computes the reverse groups and the stories of an epic from what is loaded", () => {
    const idx = index(
      [epic("E1")],
      [
        issue("S1", { epicId: "E1", dependsOn: ["S2", "S9", "S1"] }),
        issue("S2", { relates: ["S1"] }),
        issue("S3", { epicId: "E1", dependsOn: ["S1"] }),
      ],
    );
    const s1 = Object.fromEntries(linkGroups(idx, "S1").map((g) => [g.group, g.rows]));
    expect(s1.dependsOn.map((r) => [r.key, r.node?.status ?? null])).toEqual([["S2", "To do"], ["S9", null]]);
    expect(s1.dependedOnBy.map((r) => r.key)).toEqual(["S3"]);
    expect(s1.relatedFrom.map((r) => r.key)).toEqual(["S2"]);
    expect(s1.relates).toBeUndefined();
    expect(s1.children).toBeUndefined();
    const e1 = linkGroups(idx, "E1");
    expect(e1.map((g) => g.group)).toEqual(["children"]);
    expect(e1[0].rows.map((r) => r.key)).toEqual(["S1", "S3"]);
  });

  it("answers nothing for an unknown id", () => {
    expect(linkGroups(index([], []), "X")).toEqual([]);
    expect(nodeOf(index([], []), "__proto__")).toBeNull();
  });
});
