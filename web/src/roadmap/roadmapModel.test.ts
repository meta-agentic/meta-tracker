import { describe, expect, it } from "vitest";
import {
  LEAD_DAYS,
  MIN_SPAN_DAYS,
  TRAIL_DAYS,
  addDays,
  barSpan,
  edgeDates,
  moveEdge,
  timelineRange,
  unionSpan,
} from "./roadmapModel";
import type { Issue } from "../store/types";

const ORIGIN = "2025-01-01";

function issue(startDate: string | null, dueDate: string | null): Issue {
  return {
    id: "i",
    key: "K-1",
    boardId: "b",
    epicId: null,
    columnId: "c",
    title: "t",
    order: 0,
    startDate,
    dueDate,
    storyPoints: null,
    type: null,
    labels: [],
    priority: null,
    description: null,
    dependsOn: [],
    relates: [],
  };
}

describe("barSpan", () => {
  it("runs from the start day to the due day", () => {
    expect(barSpan(issue("2025-01-03", "2025-01-08"), ORIGIN)).toEqual({ start: 2, end: 7 });
  });

  it("is never narrower than a day, and has no bar without a date", () => {
    expect(barSpan(issue("2025-01-03", "2025-01-03"), ORIGIN)).toEqual({ start: 2, end: 3 });
    expect(barSpan(issue("2025-01-03", null), ORIGIN)).toEqual({ start: 2, end: 3 });
    expect(barSpan(issue(null, "2025-01-03"), ORIGIN)).toEqual({ start: 2, end: 3 });
    expect(barSpan(issue(null, null), ORIGIN)).toBeNull();
  });

  it("crosses a daylight-saving change without losing a day", () => {
    const origin = "2025-03-01";
    expect(barSpan(issue("2025-03-29", "2025-04-02"), origin)).toEqual({ start: 28, end: 32 });
    expect(addDays(origin, 32)).toBe("2025-04-02");
  });
});

describe("unionSpan", () => {
  it("spans every bar and skips the missing ones", () => {
    expect(unionSpan([{ start: 4, end: 6 }, null, { start: 1, end: 3 }])).toEqual({ start: 1, end: 6 });
    expect(unionSpan([null])).toBeNull();
  });
});

describe("timelineRange", () => {
  it("pads the dated items on both sides", () => {
    const range = timelineRange([issue("2025-02-01", "2025-09-01"), issue(null, null)], ORIGIN);
    expect(range.originDate).toBe(addDays("2025-02-01", -LEAD_DAYS));
    expect(addDays(range.originDate, range.totalDays)).toBe(addDays("2025-09-01", TRAIL_DAYS + 1));
  });

  it("falls back to a fixed window when nothing is dated", () => {
    expect(timelineRange([issue(null, null)], ORIGIN)).toEqual({
      originDate: addDays(ORIGIN, -LEAD_DAYS),
      totalDays: MIN_SPAN_DAYS,
    });
  });
});

describe("moveEdge", () => {
  const span = { start: 10, end: 15 };

  it("moves only the dragged edge", () => {
    expect(moveEdge(span, "start", -3, 100)).toEqual({ start: 7, end: 15 });
    expect(moveEdge(span, "end", 4, 100)).toEqual({ start: 10, end: 19 });
  });

  it("keeps the bar a day wide when an edge overshoots the other", () => {
    expect(moveEdge(span, "start", 50, 100)).toEqual({ start: 14, end: 15 });
    expect(moveEdge(span, "end", -50, 100)).toEqual({ start: 10, end: 11 });
  });

  it("stays inside the timeline", () => {
    expect(moveEdge(span, "start", -50, 100)).toEqual({ start: 0, end: 15 });
    expect(moveEdge(span, "end", 500, 100)).toEqual({ start: 10, end: 100 });
  });
});

describe("edgeDates", () => {
  it("writes the dragged edge's field and keeps the other as stored", () => {
    const stored = issue("2025-01-03", "2025-01-03");
    expect(edgeDates(stored, { start: 0, end: 3 }, "start", ORIGIN)).toEqual({
      startDate: "2025-01-01",
      dueDate: "2025-01-03",
    });
    expect(edgeDates(stored, { start: 2, end: 9 }, "end", ORIGIN)).toEqual({
      startDate: "2025-01-03",
      dueDate: "2025-01-10",
    });
  });

  it("fills the missing date from the bar", () => {
    expect(edgeDates(issue(null, "2025-01-03"), { start: 2, end: 6 }, "end", ORIGIN)).toEqual({
      startDate: "2025-01-03",
      dueDate: "2025-01-07",
    });
  });
});
