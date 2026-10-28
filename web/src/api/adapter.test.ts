import { describe, expect, it } from "vitest";
import { toWorkspaceSnapshot } from "./adapter";
import {
  boardFixture,
  epicItemFixture,
  itemFixture,
  workspaceFixture,
} from "../test/recordedTransport";

const base = { workspace: workspaceFixture, boards: [boardFixture] };

describe("toWorkspaceSnapshot", () => {
  it("keys each column by its column key, in ordinal order, with the ordinal as its order", () => {
    const { boards } = toWorkspaceSnapshot({ ...base, items: [] });

    expect(boards).toHaveLength(1);
    expect(boards[0].columns).toEqual([
      { id: "todo", name: "To Do", order: 0 },
      { id: "doing", name: "In Progress", order: 1 },
    ]);
  });

  it("keys a board by its own key, and a second board by its own", () => {
    const second = { ...boardFixture, key: "VEC-2", name: "Platform" };
    const { boards } = toWorkspaceSnapshot({ ...base, boards: [boardFixture, second], items: [] });
    expect(boards.map((b) => [b.id, b.key, b.name])).toEqual([
      ["VEC", "VEC", "Delivery"],
      ["VEC-2", "VEC-2", "Platform"],
    ]);
  });

  it("places an item in the column and on the board its keys name", () => {
    const second = { ...boardFixture, key: "VEC-2", name: "Platform" };
    const { issues } = toWorkspaceSnapshot({
      ...base,
      boards: [boardFixture, second],
      items: [
        itemFixture({ key: "VEC-7", column: "doing" }),
        itemFixture({ key: "VEC-8", board: "VEC-2", column: "todo" }),
      ],
    });

    expect(issues.map((i) => [i.id, i.boardId, i.columnId])).toEqual([
      ["VEC-7", "VEC", "doing"],
      ["VEC-8", "VEC-2", "todo"],
    ]);
  });

  it("leaves a column that is not on the board off it, and keeps its items as issues", () => {
    const withBacklog = {
      ...boardFixture,
      columns: [...boardFixture.columns, { key: "backlog", name: "Backlog", ordinal: 2, onBoard: false }],
    };
    const { boards, issues } = toWorkspaceSnapshot({
      ...base,
      boards: [withBacklog],
      items: [itemFixture({ key: "VEC-9", column: "backlog" })],
    });

    expect(boards[0].columns.map((c) => c.id)).toEqual(["todo", "doing"]);
    expect(issues.map((i) => [i.id, i.columnId])).toEqual([["VEC-9", "backlog"]]);
  });

  it("lifts items marked as epics out of the issue list", () => {
    const { epics, issues } = toWorkspaceSnapshot({
      ...base,
      items: [epicItemFixture, itemFixture()],
    });

    expect(epics).toEqual([
      { id: "VEC-1", key: "VEC-1", title: "Client shell", color: "#123456" },
    ]);
    expect(issues.map((i) => i.id)).toEqual(["VEC-2"]);
  });

  it("falls back to a stable colour for an epic that stores none", () => {
    const uncoloured = { ...epicItemFixture, fields: { type: "epic" } };
    const first = toWorkspaceSnapshot({ ...base, items: [uncoloured] }).epics[0];
    const second = toWorkspaceSnapshot({ ...base, items: [uncoloured] }).epics[0];

    expect(first.color).toMatch(/^#[0-9a-f]{6}$/);
    expect(second.color).toBe(first.color);
  });

  it("projects sparse ranks to dense per-column order, preserving rank order", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [
        // Newest first, as the server sends them: response order is not rank order.
        itemFixture({ key: "VEC-6", rank: "b", column: "doing" }),
        itemFixture({ key: "VEC-5", rank: "z" }),
        itemFixture({ key: "VEC-4", rank: "n" }),
        itemFixture({ key: "VEC-3", rank: "b" }),
      ],
    });

    const byId = new Map(issues.map((i) => [i.id, i]));
    expect(byId.get("VEC-3")?.order).toBe(0);
    expect(byId.get("VEC-4")?.order).toBe(1);
    expect(byId.get("VEC-5")?.order).toBe(2);
    // A different column restarts the dense index.
    expect(byId.get("VEC-6")?.order).toBe(0);
  });

  it("breaks a rank tie on the key, not on the response order", () => {
    const forwards = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({ key: "VEC-9", rank: "m" }),
        itemFixture({ key: "VEC-8", rank: "m" }),
      ],
    });
    const backwards = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({ key: "VEC-8", rank: "m" }),
        itemFixture({ key: "VEC-9", rank: "m" }),
      ],
    });

    const order = (s: typeof forwards) =>
      new Map(s.issues.map((i) => [i.id, i.order]));
    expect(order(forwards).get("VEC-8")).toBe(0);
    expect(order(forwards)).toEqual(order(backwards));
  });

  it("links an issue to its parent epic by key and drops a dangling parent", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [
        epicItemFixture,
        itemFixture({ key: "VEC-3", fields: { parentId: "VEC-1" } }),
        itemFixture({ key: "VEC-4", fields: { parentId: "VEC-404" } }),
      ],
    });

    const byId = new Map(issues.map((i) => [i.id, i]));
    expect(byId.get("VEC-3")?.epicId).toBe("VEC-1");
    expect(byId.get("VEC-4")?.epicId).toBeNull();
  });

  it("reads schedule and estimate out of the open field document", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({
          fields: { startDate: "2026-01-05", dueDate: "2026-02-01", storyPoints: 5 },
        }),
      ],
    });

    expect(issues[0]).toMatchObject({
      startDate: "2026-01-05",
      dueDate: "2026-02-01",
      storyPoints: 5,
    });
  });

  it("nulls a malformed or absent optional field rather than guessing", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [itemFixture({ fields: { storyPoints: "five", dueDate: 17 } })],
    });

    expect(issues[0].storyPoints).toBeNull();
    expect(issues[0].dueDate).toBeNull();
    expect(issues[0].startDate).toBeNull();
  });

  it("reads type, labels, priority, description and links out of the field document", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({
          fields: {
            type: "story",
            labels: ["web", "design"],
            priority: "P1",
            description: "First line\nSecond line",
            dependencies: ["VEC-7"],
            relates: ["VEC-8", "VEC-9"],
          },
        }),
      ],
    });

    expect(issues[0]).toMatchObject({
      type: "story",
      labels: ["web", "design"],
      priority: "P1",
      description: "First line\nSecond line",
      dependsOn: ["VEC-7"],
      relates: ["VEC-8", "VEC-9"],
    });
  });

  it("keeps only the distinct string entries of a list field, and empties a non-list", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({
          fields: { labels: ["web", 3, "", null, "api", "web"], dependencies: "VEC-7", relates: {} },
        }),
      ],
    });

    expect(issues[0].labels).toEqual(["web", "api"]);
    expect(issues[0].dependsOn).toEqual([]);
    expect(issues[0].relates).toEqual([]);
    expect(issues[0].type).toBeNull();
    expect(issues[0].description).toBeNull();
  });

  it("tolerates a type named after an object prototype member", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [itemFixture({ fields: { type: "constructor" } })],
    });
    expect(issues[0].type).toBe("constructor");
  });

  it("carries the workspace identity the app shell names", () => {
    const { workspace } = toWorkspaceSnapshot({ ...base, items: [] });
    expect(workspace).toEqual({ key: "VEC", name: "Vectis" });
  });

  it("drops an item whose board the payload does not carry", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [itemFixture({ key: "VEC-3", board: "ABSENT" })],
    });

    expect(issues).toHaveLength(0);
  });
});
