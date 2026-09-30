import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { IssueCard } from "./IssueCard";
import { AppProviders } from "../providers/AppProviders";
import type { Issue } from "../store/types";

afterEach(() => {
  vi.restoreAllMocks();
});

const issue: Issue = {
  id: "i1",
  key: "VEC-1",
  boardId: "b1",
  epicId: null,
  columnId: "todo",
  title: "Duplicate labels",
  order: 0,
  startDate: null,
  dueDate: null,
  storyPoints: null,
  type: "story",
  labels: ["ux", "ux"],
  priority: null,
  description: null,
  dependsOn: [],
  relates: [],
};

describe("IssueCard", () => {
  it("renders duplicate labels without a React key collision", () => {
    // The adapter de-duplicates labels, but the card must not depend on it.
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <AppProviders>
        <IssueCard issue={issue} epic={null} onOpen={() => {}} />
      </AppProviders>,
    );

    expect(screen.getAllByText("ux")).toHaveLength(2);
    expect(error.mock.calls.flat().join(" ")).not.toMatch(/same key/i);
  });
});
