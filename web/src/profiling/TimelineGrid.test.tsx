import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { TimelineGrid } from "./TimelineGrid";
import { generateWorkspace } from "../lib/synthetic";
import { AppProviders } from "../providers/AppProviders";

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
      // The grid labels its day axis through the active locale, so it needs the
      // providers the app root supplies.
      <AppProviders>
        <TimelineGrid issues={issues} originDate="2024-01-01" totalDays={4 * 365} />
      </AppProviders>,
    );

    const rows = screen.queryAllByTestId("timeline-row");
    // Vertical virtualization: only a window of rows is ever mounted, never 5,000.
    expect(rows.length).toBeLessThan(200);
    expect(rows.length).toBeLessThan(issues.length);
  });
});
