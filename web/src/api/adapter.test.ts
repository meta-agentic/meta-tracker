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
  it("orders board columns by position and projects position to order", () => {
    const { boards } = toWorkspaceSnapshot({ ...base, items: [] });

    expect(boards).toHaveLength(1);
    expect(boards[0].columns.map((c) => c.id)).toEqual(["col-todo", "col-doing"]);
    expect(boards[0].columns.map((c) => c.order)).toEqual([0, 1]);
  });

  it("takes the board key from the workspace, which is what mints item keys", () => {
    const { boards } = toWorkspaceSnapshot({ ...base, items: [] });
    expect(boards[0].key).toBe("VEC");
  });

  it("lifts items marked as epics out of the issue list", () => {
    const { epics, issues } = toWorkspaceSnapshot({
      ...base,
      items: [epicItemFixture, itemFixture()],
    });

    expect(epics).toEqual([
      { id: "epic-1", key: "VEC-1", title: "Client shell", color: "#123456" },
    ]);
    expect(issues.map((i) => i.id)).toEqual(["item-1"]);
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
        itemFixture({ id: "c", key: "VEC-5", rank: "z" }),
        itemFixture({ id: "a", key: "VEC-3", rank: "b" }),
        itemFixture({ id: "b", key: "VEC-4", rank: "n" }),
        itemFixture({ id: "d", key: "VEC-6", rank: "b", columnId: "col-doing" }),
      ],
    });

    const byId = new Map(issues.map((i) => [i.id, i]));
    expect(byId.get("a")?.order).toBe(0);
    expect(byId.get("b")?.order).toBe(1);
    expect(byId.get("c")?.order).toBe(2);
    // A different column restarts the dense index.
    expect(byId.get("d")?.order).toBe(0);
  });

  it("breaks a rank tie on the key, not on the response order", () => {
    const forwards = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({ id: "x", key: "VEC-9", rank: "m" }),
        itemFixture({ id: "y", key: "VEC-8", rank: "m" }),
      ],
    });
    const backwards = toWorkspaceSnapshot({
      ...base,
      items: [
        itemFixture({ id: "y", key: "VEC-8", rank: "m" }),
        itemFixture({ id: "x", key: "VEC-9", rank: "m" }),
      ],
    });

    const order = (s: typeof forwards) =>
      new Map(s.issues.map((i) => [i.id, i.order]));
    expect(order(forwards).get("y")).toBe(0);
    expect(order(forwards)).toEqual(order(backwards));
  });

  it("links an issue to its parent epic and drops a dangling parent", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [
        epicItemFixture,
        itemFixture({ id: "linked", key: "VEC-3", fields: { parentId: "epic-1" } }),
        itemFixture({ id: "orphan", key: "VEC-4", fields: { parentId: "epic-404" } }),
      ],
    });

    const byId = new Map(issues.map((i) => [i.id, i]));
    expect(byId.get("linked")?.epicId).toBe("epic-1");
    expect(byId.get("orphan")?.epicId).toBeNull();
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

  it("drops an item whose board the payload does not carry", () => {
    const { issues } = toWorkspaceSnapshot({
      ...base,
      items: [itemFixture({ id: "elsewhere", boardId: "board-absent" })],
    });

    expect(issues).toHaveLength(0);
  });
});
