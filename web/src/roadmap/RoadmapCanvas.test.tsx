import { beforeEach, describe, expect, it } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { RoadmapCanvas } from "./RoadmapCanvas";
import { AppProviders } from "../providers/AppProviders";
import { generateWorkspace } from "../lib/synthetic";
import { workspaceStore } from "../store/workspaceStore";
import { selectFlatTree } from "../store/selectors";
import { giveElementSize } from "../test/layout";
import type { Issue, WorkspaceSnapshot } from "../store/types";

const VIEWPORT = { width: 1000, height: 600 };
const ROW = 36;
const DAY = 24;
giveElementSize("roadmap-scroll", VIEWPORT);

// jsdom has no PointerEvent, and fireEvent falls back to a bare Event that
// drops clientX and button. A MouseEvent carries both.
if (!("PointerEvent" in window)) {
  class PointerEventShim extends MouseEvent {
    readonly pointerId: number;
    constructor(type: string, init: PointerEventInit = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 0;
    }
  }
  Object.defineProperty(window, "PointerEvent", { value: PointerEventShim, configurable: true });
}

function mount(boardId: string) {
  let stats = { rows: 0, columns: 0 };
  render(
    <AppProviders>
      <RoadmapCanvas
        boardId={boardId}
        rowHeight={ROW}
        dayWidth={DAY}
        height={VIEWPORT.height}
        onRenderStats={(next) => {
          stats = next;
        }}
      />
    </AppProviders>,
  );
  return { stats: () => stats, scroller: screen.getByTestId("roadmap-scroll") };
}

function scroll(scroller: HTMLElement, to: { top?: number; left?: number }) {
  act(() => {
    if (to.top !== undefined) scroller.scrollTop = to.top;
    if (to.left !== undefined) scroller.scrollLeft = to.left;
    fireEvent.scroll(scroller);
  });
}

/** Each pane's rendered rows as id → vertical offset. */
function paneRows(testId: string): Map<string, string> {
  return new Map(
    screen.getAllByTestId(testId).map((row) => [row.dataset.rowId!, row.style.top]),
  );
}

function expectPanesAligned() {
  const ledger = paneRows("roadmap-ledger-row");
  const gantt = paneRows("roadmap-gantt-row");
  expect(ledger.size).toBeGreaterThan(0);
  expect([...gantt]).toEqual([...ledger]);
}

describe("RoadmapCanvas alignment and virtualization", () => {
  const snapshot = generateWorkspace({ boards: 1, epics: 12, issues: 4_000, seed: 5 });
  const epicIds = snapshot.epics.map((epic) => epic.id);

  beforeEach(() => {
    localStorage.clear();
    act(() => {
      workspaceStore.getState().ingestSnapshot(snapshot);
      workspaceStore.getState().setTreeExpanded(epicIds);
    });
  });

  it("renders the same viewport-bounded window of rows in both panes", () => {
    const { stats } = mount("board-0");

    const ledger = paneRows("roadmap-ledger-row");
    expect(ledger.size).toBeGreaterThan(VIEWPORT.height / ROW - 1);
    expect(ledger.size).toBeLessThan(VIEWPORT.height / ROW + 20);
    expectPanesAligned();
    expect(stats().rows).toBe(ledger.size);
    // The day axis is windowed too, never the whole multi-year span.
    expect(stats().columns).toBeGreaterThan(0);
    expect(stats().columns).toBeLessThan(VIEWPORT.width / DAY + 20);
  });

  it("keeps both panes on the same rows after a vertical scroll", () => {
    const { scroller } = mount("board-0");
    const rows = selectFlatTree(workspaceStore.getState(), "board-0", new Set(epicIds));
    const index = 1_500;
    scroll(scroller, { top: index * ROW });

    expectPanesAligned();
    const ledger = paneRows("roadmap-ledger-row");
    expect(ledger.get(rows[index].id)).toBe(`${index * ROW}px`);
    expect(ledger.has(rows[0].id)).toBe(false);
  });

  it("moves only the Gantt pane's columns on a horizontal scroll", () => {
    const { scroller, stats } = mount("board-0");
    const before = paneRows("roadmap-ledger-row");
    const columnsBefore = stats().columns;

    scroll(scroller, { left: 400 * DAY });

    expect(paneRows("roadmap-ledger-row")).toEqual(before);
    expectPanesAligned();
    expect(stats().columns).toBeLessThan(columnsBefore + 20);
  });

  it("stays aligned when an epic is collapsed from the ledger, and shares the backlog's expansion", () => {
    mount("board-0");
    const toggles = within(screen.getByTestId("roadmap-ledger")).getAllByRole("button");
    const first = toggles[0];
    expect(first).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(first);

    expect(first).toHaveAttribute("aria-expanded", "false");
    expect(workspaceStore.getState().treeExpanded).toHaveLength(epicIds.length - 1);
    expectPanesAligned();
  });
});

function item(id: string, startDate: string | null, dueDate: string | null): Issue {
  return {
    id,
    key: id.toUpperCase(),
    boardId: "b1",
    epicId: "e1",
    columnId: "c1",
    title: `Item ${id}`,
    order: 0,
    startDate,
    dueDate,
    storyPoints: null,
    type: "story",
    labels: [],
    priority: null,
    description: null,
    dependsOn: [],
    relates: [],
  };
}

describe("RoadmapCanvas edge drag", () => {
  const small: WorkspaceSnapshot = {
    boards: [{ id: "b1", key: "B", name: "Board", columns: [{ id: "c1", name: "Todo", order: 0 }] }],
    epics: [{ id: "e1", key: "E-1", title: "Epic", color: "#4f46e5" }],
    issues: [item("k-1", "2025-01-10", "2025-01-15"), item("k-2", null, null)],
  };
  const stored = (id: string) => workspaceStore.getState().issuesById[id];
  const bar = (id: string) =>
    screen.getAllByTestId("roadmap-bar").find((el) => el.dataset.barId === id);

  beforeEach(() => {
    localStorage.clear();
    act(() => {
      workspaceStore.getState().ingestSnapshot(small);
      workspaceStore.getState().setTreeExpanded(["e1"]);
    });
  });

  it("drags the due edge and writes the new due date back", () => {
    mount("b1");
    const handle = screen.getByRole("slider", { name: "Due date of K-1" });

    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientX: 500 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientX: 500 + 3 * DAY + 5 });
    // The bar previews the drag before anything is written.
    expect(bar("k-1")!.style.width).toBe(`${8 * DAY}px`);
    expect(stored("k-1").dueDate).toBe("2025-01-15");

    fireEvent.pointerUp(handle, { pointerId: 1, clientX: 500 + 3 * DAY + 5 });

    expect(stored("k-1")).toMatchObject({ startDate: "2025-01-10", dueDate: "2025-01-18" });
  });

  it("clamps a start edge dragged past the due edge to a one-day bar", () => {
    mount("b1");
    const handle = screen.getByRole("slider", { name: "Start date of K-1" });

    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientX: 100 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientX: 100 + 20 * DAY });
    fireEvent.pointerUp(handle, { pointerId: 1 });

    expect(stored("k-1")).toMatchObject({ startDate: "2025-01-14", dueDate: "2025-01-15" });
  });

  it("abandons a drag on Escape", () => {
    mount("b1");
    const handle = screen.getByRole("slider", { name: "Due date of K-1" });

    fireEvent.pointerDown(handle, { button: 0, pointerId: 1, clientX: 100 });
    fireEvent.pointerMove(handle, { pointerId: 1, clientX: 100 + 4 * DAY });
    fireEvent.keyDown(handle, { key: "Escape" });
    fireEvent.pointerUp(handle, { pointerId: 1 });

    expect(stored("k-1").dueDate).toBe("2025-01-15");
  });

  it("nudges an edge from the keyboard and keeps the canvas still when the origin moves", () => {
    const { scroller } = mount("b1");
    scroll(scroller, { left: 40 });
    const start = screen.getByRole("slider", { name: "Start date of K-1" });

    fireEvent.keyDown(start, { key: "ArrowRight" });
    expect(stored("k-1").startDate).toBe("2025-01-11");

    // A week before the earliest date: the origin moves left with it, the
    // scroll position follows, and the untouched due edge stays put on screen.
    const dueOnScreen = () => {
      const el = bar("k-1")!;
      return parseFloat(el.style.left) + parseFloat(el.style.width) - scroller.scrollLeft;
    };
    const before = dueOnScreen();
    fireEvent.keyDown(start, { key: "ArrowLeft", shiftKey: true });
    expect(stored("k-1").startDate).toBe("2025-01-04");
    expect(dueOnScreen()).toBe(before);
  });

  it("draws no bar for an undated item but keeps its row in both panes", () => {
    mount("b1");
    expect(bar("k-2")).toBeUndefined();
    expect(paneRows("roadmap-gantt-row").has("k-2")).toBe(true);
    expectPanesAligned();
  });
});
