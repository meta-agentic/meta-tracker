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

// Rows from the scroll offset down: the window, without the active row, which
// is rendered wherever it is.
const inWindow = (row: HTMLElement, offset: number) => parseFloat(row.style.top) >= offset;

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
      .filter((row) => inWindow(row, offset) && parseFloat(row.style.top) < parseFloat(target.style.top))
      .map((row) => [row.dataset.rowId, row.style.top]);
    expect(above.length).toBeGreaterThan(0);

    fireEvent.click(target);
    expect(target).toHaveAttribute("aria-expanded", "false");
    expect(stats().scrollOffset).toBe(offset);
    const aboveAfter = screen
      .getAllByTestId("backlog-row")
      .filter((row) => inWindow(row, offset) && parseFloat(row.style.top) < parseFloat(target.style.top))
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

  it("keeps the rows on screen still when a collapse near the end shortens the tree", () => {
    act(() => workspaceStore.getState().setTreeExpanded(epicIds));
    const { stats, scroller } = mount();
    const rows = selectFlatTree(workspaceStore.getState(), "board-0", new Set(epicIds));
    // The last epic's stories run to the end of the list: collapsing it with
    // the epic near the top of the window leaves far less than a window below.
    const lastEpic = rows.map((row) => row.kind).lastIndexOf("epic");
    const offset = (lastEpic - 2) * ROW;
    scrollTo(scroller, offset);
    const canvas = scroller.firstElementChild as HTMLElement;
    const target = screen
      .getAllByTestId("backlog-row")
      .find((row) => row.dataset.rowId === rows[lastEpic].id)!;
    const above = screen
      .getAllByTestId("backlog-row")
      .filter((row) => inWindow(row, offset) && parseFloat(row.style.top) < parseFloat(target.style.top))
      .map((row) => [row.dataset.rowId, row.style.top]);

    fireEvent.click(target);

    expect(target).toHaveAttribute("aria-expanded", "false");
    // The rows alone now end above the window's bottom; a trailing spacer keeps
    // the scrollable height there, so the browser has nothing to clamp.
    expect((lastEpic + 1) * ROW).toBeLessThan(offset + VIEWPORT.height);
    expect(parseFloat(canvas.style.height)).toBeGreaterThanOrEqual(offset + VIEWPORT.height);
    expect(stats().scrollOffset).toBe(offset);
    expect(
      screen
        .getAllByTestId("backlog-row")
        .filter((row) => inWindow(row, offset) && parseFloat(row.style.top) < parseFloat(target.style.top))
        .map((row) => [row.dataset.rowId, row.style.top]),
    ).toEqual(above);

    // Scrolled back to the top, the spacer has nothing left to hold.
    scrollTo(scroller, 0);
    expect(parseFloat(canvas.style.height)).toBe((rows.length - (rows.length - lastEpic - 1)) * ROW);
  });

  it("always renders the row aria-activedescendant names", () => {
    act(() => workspaceStore.getState().setTreeExpanded(epicIds));
    const { scroller } = mount();
    const tree = screen.getByRole("tree");
    fireEvent.keyDown(tree, { key: "Home" });

    // Far past the virtualized window: the active row is still in the DOM.
    scrollTo(scroller, 2_000 * ROW);
    const active = tree.getAttribute("aria-activedescendant")!;
    expect(document.getElementById(active)).not.toBeNull();
    expect(renderedIds().length).toBeLessThan(VIEWPORT.height / ROW + 20);
  });

  it("hands activity to the nearest visible ancestor when the active row is hidden", () => {
    mount();
    const tree = screen.getByRole("tree");
    // The second epic, not the first: row 0 is where activity lands by default.
    fireEvent.keyDown(tree, { key: "ArrowDown" });
    fireEvent.keyDown(tree, { key: "ArrowRight" });
    fireEvent.keyDown(tree, { key: "ArrowRight" });
    const epic = workspaceStore.getState().treeExpanded[0];
    expect(renderedIds()[0]).not.toBe(epic);
    expect(tree.getAttribute("aria-activedescendant")).not.toBe(`backlog-row-${epic}`);

    // Collapse all, from outside the tree.
    act(() => workspaceStore.getState().setTreeExpanded([]));
    expect(tree).toHaveAttribute("aria-activedescendant", `backlog-row-${epic}`);
  });

  it("shows an epic with stories on two boards on both, each with its own count and points", () => {
    const column = { id: "todo", name: "To Do", order: 0 };
    const story = (id: string, boardId: string, storyPoints: number) => ({
      id, key: id.toUpperCase(), boardId, epicId: "e1", columnId: "todo", title: id, order: 0,
      startDate: null, dueDate: null, storyPoints, type: "story", labels: [], priority: null,
      description: null, dependsOn: [], relates: [],
    });
    act(() =>
      workspaceStore.getState().ingestSnapshot({
        boards: [
          { id: "b-a", key: "A", name: "Alpha", columns: [column] },
          { id: "b-b", key: "B", name: "Beta", columns: [column] },
        ],
        epics: [{ id: "e1", key: "E-1", title: "Shared", color: "red" }],
        issues: [story("a1", "b-a", 3), story("b1", "b-b", 5), story("b2", "b-b", 2)],
      }),
    );
    const epicRow = (boardId: string) => {
      const view = render(
        <AppProviders>
          <BacklogTree boardId={boardId} height={VIEWPORT.height} rowHeight={ROW} />
        </AppProviders>,
      );
      const row = screen.getByRole("treeitem", { name: /Shared/ });
      const shown = {
        count: row.querySelector(".vec-tree__count span[aria-hidden]")?.textContent,
        points: row.querySelector(".vec-points [aria-hidden]")?.textContent,
      };
      view.unmount();
      return shown;
    };

    expect(epicRow("b-a")).toEqual({ count: "1", points: "3" });
    expect(epicRow("b-b")).toEqual({ count: "2", points: "7" });
  });
});
