import { beforeEach, describe, expect, it } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { BacklogTree } from "./BacklogTree";
import { AppProviders } from "../providers/AppProviders";
import { generateWorkspace } from "../lib/synthetic";
import { workspaceStore } from "../store/workspaceStore";
import { selectFlatTree } from "../store/selectors";
import { giveElementSize } from "../test/layout";

const VIEWPORT = { width: 800, height: 600 };
const ROW = 36;
giveElementSize("backlog-scroll", VIEWPORT);

const snapshot = generateWorkspace({ boards: 1, epics: 12, issues: 4_000, seed: 5 });
const epicIds = snapshot.epics.map((epic) => epic.id);

beforeEach(() => {
  localStorage.clear();
  act(() => {
    workspaceStore.getState().ingestSnapshot(snapshot);
    workspaceStore.getState().setTreeExpanded([]);
  });
});

function mount() {
  let stats = { rows: 0, scrollOffset: 0 };
  const view = render(
    <AppProviders>
      <BacklogTree
        boardId="board-0"
        height={VIEWPORT.height}
        rowHeight={ROW}
        onRenderStats={(next) => {
          stats = next;
        }}
      />
    </AppProviders>,
  );
  return { view, stats: () => stats, scroller: screen.getByTestId("backlog-scroll") };
}

function scrollTo(scroller: HTMLElement, top: number) {
  act(() => {
    scroller.scrollTop = top;
    fireEvent.scroll(scroller);
  });
}

const renderedIds = () => screen.getAllByTestId("backlog-row").map((row) => row.dataset.rowId);

describe("BacklogTree", () => {
  it("renders a viewport-bounded window of a fully expanded 4,000-issue tree", () => {
    act(() => workspaceStore.getState().setTreeExpanded(epicIds));
    const { stats } = mount();

    const rows = screen.getAllByTestId("backlog-row");
    // A real window, not an empty one…
    expect(rows.length).toBeGreaterThan(VIEWPORT.height / ROW - 1);
    // …and bounded by the viewport plus overscan, never by the 4,012 rows.
    expect(rows.length).toBeLessThan(VIEWPORT.height / ROW + 20);
    expect(stats().rows).toBe(rows.length);
  });

  it("leaves the scroll offset and every row above alone when a row is toggled", () => {
    act(() => workspaceStore.getState().setTreeExpanded(epicIds));
    const { stats, scroller } = mount();
    // Scroll so the second epic sits a few rows below the top of the window.
    const rows = selectFlatTree(workspaceStore.getState(), "board-0", new Set(epicIds));
    const epicIndex = rows.findIndex((row, index) => index > 0 && row.kind === "epic");
    const offset = (epicIndex - 5) * ROW;
    scrollTo(scroller, offset);
    expect(stats().scrollOffset).toBe(offset);

    const target = screen
      .getAllByTestId("backlog-row")
      .find((row) => row.dataset.rowId === rows[epicIndex].id)!;
    expect(target).toHaveAttribute("aria-expanded", "true");
    const above = screen
      .getAllByTestId("backlog-row")
      .filter((row) => parseFloat(row.style.top) < parseFloat(target.style.top))
      .map((row) => [row.dataset.rowId, row.style.top]);
    expect(above.length).toBeGreaterThan(0);

    fireEvent.click(target);
    expect(target).toHaveAttribute("aria-expanded", "false");
    expect(stats().scrollOffset).toBe(offset);
    const aboveAfter = screen
      .getAllByTestId("backlog-row")
      .filter((row) => parseFloat(row.style.top) < parseFloat(target.style.top))
      .map((row) => [row.dataset.rowId, row.style.top]);
    expect(aboveAfter).toEqual(above);

    fireEvent.click(target);
    expect(stats().scrollOffset).toBe(offset);
  });

  it("moves, expands and collapses from the keyboard", () => {
    mount();
    const tree = screen.getByRole("tree");
    const first = epicIds.slice().sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))[0];

    expect(renderedIds()).toHaveLength(epicIds.length);
    expect(tree).toHaveAttribute("aria-activedescendant", `backlog-row-${first}`);

    fireEvent.keyDown(tree, { key: "ArrowRight" });
    expect(workspaceStore.getState().treeExpanded).toContain(first);
    expect(renderedIds().length).toBeGreaterThan(epicIds.length);

    // Into the first child, then back out to the parent, then collapse it.
    fireEvent.keyDown(tree, { key: "ArrowRight" });
    expect(tree.getAttribute("aria-activedescendant")).not.toBe(`backlog-row-${first}`);
    fireEvent.keyDown(tree, { key: "ArrowLeft" });
    expect(tree).toHaveAttribute("aria-activedescendant", `backlog-row-${first}`);
    fireEvent.keyDown(tree, { key: "ArrowLeft" });
    expect(workspaceStore.getState().treeExpanded).not.toContain(first);
  });
});
