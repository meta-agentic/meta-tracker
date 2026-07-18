import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TimelineGrid } from "./TimelineGrid";
import { generateWorkspace } from "../lib/synthetic";

describe("dual-axis virtualization", () => {
  it("keeps live DOM node count bounded far below the task count", () => {
    const { issues } = generateWorkspace({
      boards: 1,
      epics: 12,
      issues: 5_000,
      years: 4,
      seed: 11,
    });

    render(
      <TimelineGrid issues={issues} originDate="2024-01-01" totalDays={4 * 365} />,
    );

    const rows = screen.queryAllByTestId("timeline-row");
    // Vertical virtualization: only a window of rows is ever mounted, never 5,000.
    expect(rows.length).toBeLessThan(200);
    expect(rows.length).toBeLessThan(issues.length);
  });
});
