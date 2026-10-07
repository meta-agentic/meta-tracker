import { describe, expect, it } from "vitest";
import {
  LEAD_DAYS,
  MIN_SPAN_DAYS,
  TRAIL_DAYS,
  barSpan,
  edgeDate,
  shiftEdge,
  timelineRange,
  unionSpan,
} from "./roadmapModel";
import { addDays } from "../lib/days";
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

describe("edgeDate", () => {
  it("reads a missing date from the other, and a reversed due date at the start", () => {
    expect(edgeDate(issue("2025-01-03", "2025-01-08"), "end")).toBe("2025-01-08");
    expect(edgeDate(issue("2025-01-03", null), "end")).toBe("2025-01-03");
    expect(edgeDate(issue(null, "2025-01-03"), "start")).toBe("2025-01-03");
    expect(edgeDate(issue("2025-01-08", "2025-01-03"), "end")).toBe("2025-01-08");
    expect(edgeDate(issue(null, null), "start")).toBeNull();
  });
});

describe("shiftEdge", () => {
  const MAX = "2025-12-31";
  const shift = (start: string | null, due: string | null, edge: "start" | "end", delta: number) =>
    shiftEdge(issue(start, due), edge, delta, ORIGIN, MAX);

  it("moves only the dragged edge's field", () => {
    expect(shift("2025-01-10", "2025-01-15", "start", -3)).toMatchObject({
      startDate: "2025-01-07",
      dueDate: "2025-01-15",
    });
    expect(shift("2025-01-10", "2025-01-15", "end", 4)).toMatchObject({
      startDate: "2025-01-10",
      dueDate: "2025-01-19",
    });
  });

  it("never writes the drawn one-day minimum width back", () => {
    expect(shift("2025-01-03", "2025-01-03", "end", 6).dueDate).toBe("2025-01-09");
    expect(shift("2025-01-03", null, "end", 2).dueDate).toBe("2025-01-05");
  });

  it("does not move a same-day due date later on a move earlier", () => {
    const stored = issue("2025-01-03", "2025-01-03");
    expect(shiftEdge(stored, "end", -1, ORIGIN, MAX)).toBe(stored);
  });

  it("returns the input untouched for a zero or fully clamped move, inventing no date", () => {
    const startOnly = issue("2025-01-03", null);
    expect(shiftEdge(startOnly, "end", 0, ORIGIN, MAX)).toBe(startOnly);
    expect(shiftEdge(startOnly, "end", -5, ORIGIN, MAX)).toBe(startOnly);
    const dueOnly = issue(null, "2025-01-03");
    expect(shiftEdge(dueOnly, "start", 5, ORIGIN, MAX)).toBe(dueOnly);
  });

  it("lets a due-only item's due date move earlier", () => {
    expect(shift(null, "2025-01-10", "end", -3)).toMatchObject({
      startDate: null,
      dueDate: "2025-01-07",
    });
  });

  it("does not cross the other date, and stays inside the timeline", () => {
    expect(shift("2025-01-10", "2025-01-15", "start", 50).startDate).toBe("2025-01-15");
    expect(shift("2025-01-10", "2025-01-15", "end", -50).dueDate).toBe("2025-01-10");
    expect(shift("2025-01-10", "2025-01-15", "start", -50).startDate).toBe(ORIGIN);
    expect(shift("2025-01-10", "2025-01-15", "end", 500).dueDate).toBe(MAX);
  });

  it("moves a reversed item's end from where it is drawn", () => {
    expect(shift("2025-01-08", "2025-01-03", "end", 1).dueDate).toBe("2025-01-09");
  });
});
