import type { Issue } from "../store/types";

const DAY_MS = 86_400_000;

/** Days of empty timeline kept before the earliest and after the latest date. */
export const LEAD_DAYS = 14;
export const TRAIL_DAYS = 28;
export const MIN_SPAN_DAYS = 90;

export function addDays(iso: string, days: number): string {
  return new Date(Date.parse(iso) + days * DAY_MS).toISOString().slice(0, 10);
}

export function daysBetween(fromIso: string, toIso: string): number {
  return Math.round((Date.parse(toIso) - Date.parse(fromIso)) / DAY_MS);
}

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

/**
 * Moves one edge of a bar by `deltaDays`, keeping the bar at least one day wide
 * and inside `[0, totalDays]`. Only the dragged edge moves: the other stays put
 * even when the drag overshoots it.
 */
export function moveEdge(
  span: BarSpan,
  edge: BarEdge,
  deltaDays: number,
  totalDays: number,
): BarSpan {
  if (edge === "start") {
    return { start: Math.min(Math.max(0, span.start + deltaDays), span.end - 1), end: span.end };
  }
  return { start: span.start, end: Math.max(Math.min(totalDays, span.end + deltaDays), span.start + 1) };
}

/**
 * The dates an edge drag writes back. The dragged edge's field takes the new
 * day; the other field is kept exactly as stored, and filled from the bar only
 * when the item had no value for it, so a drag never rewrites a date it did not
 * touch.
 */
export function edgeDates(
  issue: Pick<Issue, "startDate" | "dueDate">,
  span: BarSpan,
  edge: BarEdge,
  originIso: string,
): Pick<Issue, "startDate" | "dueDate"> {
  const startDate = addDays(originIso, span.start);
  const dueDate = addDays(originIso, span.end);
  return edge === "start"
    ? { startDate, dueDate: issue.dueDate ?? dueDate }
    : { startDate: issue.startDate ?? startDate, dueDate };
}
