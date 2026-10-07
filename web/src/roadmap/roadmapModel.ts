import type { Issue } from "../store/types";
import { addDays, daysBetween } from "../lib/days";

/** Days of empty timeline kept before the earliest and after the latest date. */
export const LEAD_DAYS = 14;
export const TRAIL_DAYS = 28;
export const MIN_SPAN_DAYS = 90;

/**
 * A bar in whole days from the timeline origin, end exclusive. Same reading of
 * the two dates as `TimelineGrid`: the bar runs from the start to the due day
 * and is never narrower than one day.
 */
export interface BarSpan {
  start: number;
  end: number;
}

/** An item with only one of its dates still gets a one-day bar; with neither, none. */
export function barSpan(
  issue: Pick<Issue, "startDate" | "dueDate">,
  originIso: string,
): BarSpan | null {
  const startIso = issue.startDate ?? issue.dueDate;
  if (startIso === null) return null;
  const start = daysBetween(originIso, startIso);
  const due = issue.dueDate === null ? start : daysBetween(originIso, issue.dueDate);
  return { start, end: start + Math.max(1, due - start) };
}

/** The span of every bar in `spans`, or null when none has one. */
export function unionSpan(spans: Iterable<BarSpan | null>): BarSpan | null {
  let out: BarSpan | null = null;
  for (const span of spans) {
    if (!span) continue;
    out = out
      ? { start: Math.min(out.start, span.start), end: Math.max(out.end, span.end) }
      : { ...span };
  }
  return out;
}

export interface TimelineRange {
  originDate: string;
  totalDays: number;
}

/**
 * The days the canvas lays out: every dated item plus a margin either side, so
 * an edge has room to be dragged past the current extremes. With no dated item
 * at all, a window starting at `fallbackIso`.
 */
export function timelineRange(issues: Iterable<Issue>, fallbackIso: string): TimelineRange {
  let min: string | null = null;
  let max: string | null = null;
  for (const issue of issues) {
    for (const date of [issue.startDate, issue.dueDate]) {
      if (date === null) continue;
      if (min === null || date < min) min = date;
      if (max === null || date > max) max = date;
    }
  }
  if (min === null || max === null) {
    return { originDate: addDays(fallbackIso, -LEAD_DAYS), totalDays: MIN_SPAN_DAYS };
  }
  const originDate = addDays(min, -LEAD_DAYS);
  return {
    originDate,
    totalDays: Math.max(MIN_SPAN_DAYS, daysBetween(originDate, max) + 1 + TRAIL_DAYS),
  };
}

export type BarEdge = "start" | "end";

export type ScheduleDates = Pick<Issue, "startDate" | "dueDate">;

function clampIso(iso: string, min: string, max: string): string {
  return iso < min ? min : iso > max ? max : iso;
}

/**
 * The date an edge stands on. A missing date takes the other one, and a due
 * date before the start is drawn at the start, so edits read it there too.
 */
export function edgeDate(dates: ScheduleDates, edge: BarEdge): string | null {
  const anchor = dates.startDate ?? dates.dueDate;
  if (edge === "start" || anchor === null) return anchor;
  return dates.dueDate !== null && dates.dueDate >= anchor ? dates.dueDate : anchor;
}

/**
 * Moves one edge's date by `deltaDays`, on the stored dates rather than the
 * drawn bar: the bar's one-day minimum width is a drawing rule and must never be
 * written back. Only the moved edge's field changes; it cannot cross the other
 * date and stays within `[minIso, maxIso]`. When the clamped date equals the one
 * the edge already stands on, the input comes back unchanged, so a zero or
 * fully clamped move never invents a missing date.
 */
export function shiftEdge(
  dates: ScheduleDates,
  edge: BarEdge,
  deltaDays: number,
  minIso: string,
  maxIso: string,
): ScheduleDates {
  const base = edgeDate(dates, edge);
  if (base === null || deltaDays === 0) return dates;
  if (edge === "start") {
    const due = dates.dueDate !== null && dates.dueDate >= base ? dates.dueDate : maxIso;
    const next = clampIso(addDays(base, deltaDays), minIso, due);
    return next === base ? dates : { startDate: next, dueDate: dates.dueDate };
  }
  const next = clampIso(addDays(base, deltaDays), dates.startDate ?? minIso, maxIso);
  return next === base ? dates : { startDate: dates.startDate, dueDate: next };
}
