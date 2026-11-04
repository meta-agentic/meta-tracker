import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { AppProviders } from "../providers/AppProviders";
import { workspaceStore } from "../store/workspaceStore";
import { BoardView } from "./BoardView";
import { BoardFilterBar } from "./FilterBar";
import type { Issue, WorkspaceSnapshot } from "../store/types";

function issue(n: number, over: Partial<Issue>): Issue {
  return {
    id: `i${n}`, key: `T-${n}`, boardId: "b", epicId: null, columnId: "c", title: `Item ${n}`, order: n,
    startDate: null, dueDate: null, storyPoints: null, type: "story", labels: [], priority: null,
    description: null, dependsOn: [], relates: [], ...over,
  };
}

const snapshot: WorkspaceSnapshot = {
  boards: [{ id: "b", key: "B", name: "Board", columns: [{ id: "c", name: "To Do", order: 0 }] }],
  epics: [{ id: "e1", key: "E-1", title: "Alpha", color: "#000" }],
  issues: [issue(1, { epicId: "e1" }), issue(2, {}), issue(3, { epicId: "e1", type: "bug" }), issue(4, { columnId: "off", title: "Kept off" })],
};

function Harness() {
  return (
    <AppProviders>
      <BoardFilterBar onBoardOnly />
      <BoardView onOpenIssue={() => {}} />
    </AppProviders>
  );
}

afterEach(() => {
  vi.useRealTimers();
});

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, "", "/");
  act(() => {
    workspaceStore.getState().ingestSnapshot(snapshot);
    workspaceStore.getState().setActiveBoard("b");
  });
});

describe("FilterBar", () => {
  it("filters by an epic facet, writes the URL, counts, and clears", () => {
    render(<Harness />);
    expect(screen.getByTestId("filter-count")).toHaveTextContent("3 of 3 items on this board");

    fireEvent.click(screen.getByRole("checkbox", { name: /E-1 Alpha/ }));
    expect(window.location.search).toBe("?epic=E-1");
    expect(screen.getByTestId("filter-count")).toHaveTextContent("2 of 3 items on this board");
    expect(screen.queryByText("Item 2")).toBeNull();
    expect(screen.getByText("Item 1")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Clear filter" }));
    expect(window.location.search).toBe("");
    expect(screen.getByText("Item 2")).toBeInTheDocument();
  });

  it("counts only what the board can show", () => {
    window.history.replaceState(null, "", "/?q=kept");
    render(<Harness />);
    expect(screen.getByTestId("filter-count")).toHaveTextContent("0 of 3 items on this board");
    expect(screen.getByText("No items match the filter")).toBeInTheDocument();
  });

  it("reads the filter from the URL and says when nothing matches", () => {
    window.history.replaceState(null, "", "/?q=zzz%E0%A4%A");
    render(<Harness />);
    expect(screen.getByText("No items match the filter")).toBeInTheDocument();
    expect(screen.getByTestId("filter-count")).toHaveTextContent("0 of 3 items on this board");
  });

  it("keeps typing working when the browser refuses to write the URL, and writes it once after a pause", () => {
    vi.useFakeTimers();
    const replace = vi.spyOn(window.history, "replaceState").mockImplementation(() => {
      throw new DOMException("too many calls", "SecurityError");
    });
    render(<Harness />);
    const input = screen.getByRole("searchbox");
    fireEvent.change(input, { target: { value: "i" } });
    fireEvent.change(input, { target: { value: "it" } });
    fireEvent.change(input, { target: { value: "it " } });
    expect(input).toHaveValue("it ");
    expect(replace).not.toHaveBeenCalled();
    expect(() => act(() => void vi.advanceTimersByTime(500))).not.toThrow();
    expect(replace).toHaveBeenCalledTimes(1);
    expect(input).toHaveValue("it ");
    replace.mockRestore();
    vi.useRealTimers();
  });

  it("keeps a typed leading space", () => {
    render(<Harness />);
    const input = screen.getByRole("searchbox");
    fireEvent.change(input, { target: { value: " " } });
    expect(input).toHaveValue(" ");
  });
});
