/**
 * Dual-axis performance profiling for the virtualized timeline.
 *
 * A large timeline is stressed along two independent axes:
 *  - the *vertical* axis (task rows) — how deep the backlog is;
 *  - the *horizontal* axis (day columns) — how long the schedule spans.
 *
 * Virtualization cost is not symmetric between them, so this module drives a
 * scripted scroll sweep that isolates each axis (vertical-only, horizontal-only)
 * and then exercises both together (diagonal). Per-axis frame-time summaries let
 * you attribute cost to the axis that actually dominates instead of guessing.
 */

export type SweepAxis = "vertical" | "horizontal" | "diagonal";

export interface SweepStop {
  axis: SweepAxis;
  /** Progress along the swept axis, 0 → 1. */
  fraction: number;
  top: number;
  left: number;
}

export interface ScrollAxis {
  /** Total scrollable size in px (e.g. rows * rowHeight). */
  scrollSize: number;
  /** Visible viewport size in px along the same dimension. */
  viewport: number;
}

const AXES: SweepAxis[] = ["vertical", "horizontal", "diagonal"];

function lerpStops(max: number, steps: number): number[] {
  if (steps <= 0) return [0];
  const out: number[] = [];
  for (let i = 0; i <= steps; i++) out.push((max * i) / steps);
  return out;
}

/**
 * Build a reproducible set of scroll positions that sweeps each axis in
 * isolation and then both together. `stepsPerAxis` stops are sampled per phase
 * (plus the origin), so the plan length is `3 * (stepsPerAxis + 1)`.
 */
export function planDualAxisSweep(
  vertical: ScrollAxis,
  horizontal: ScrollAxis,
  stepsPerAxis: number,
): SweepStop[] {
  const maxTop = Math.max(0, vertical.scrollSize - vertical.viewport);
  const maxLeft = Math.max(0, horizontal.scrollSize - horizontal.viewport);
  const tops = lerpStops(maxTop, stepsPerAxis);
  const lefts = lerpStops(maxLeft, stepsPerAxis);

  const stops: SweepStop[] = [];
  for (const axis of AXES) {
    for (let i = 0; i <= stepsPerAxis; i++) {
      const fraction = stepsPerAxis <= 0 ? 0 : i / stepsPerAxis;
      stops.push({
        axis,
        fraction,
        top: axis === "horizontal" ? 0 : tops[i],
        left: axis === "vertical" ? 0 : lefts[i],
      });
    }
  }
  return stops;
}

export interface MetricSummary {
  p50: number;
  p95: number;
  max: number;
  mean: number;
  count: number;
}

/** Linear-interpolated percentile over an unsorted sample set. `p` in [0,100]. */
export function percentile(samples: number[], p: number): number {
  if (samples.length === 0) return 0;
  const sorted = [...samples].sort((a, b) => a - b);
  if (sorted.length === 1) return sorted[0];
  const rank = (p / 100) * (sorted.length - 1);
  const lo = Math.floor(rank);
  const hi = Math.ceil(rank);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo);
}

export function summarize(samples: number[]): MetricSummary {
  if (samples.length === 0) {
    return { p50: 0, p95: 0, max: 0, mean: 0, count: 0 };
  }
  const sum = samples.reduce((a, b) => a + b, 0);
  return {
    p50: percentile(samples, 50),
    p95: percentile(samples, 95),
    max: Math.max(...samples),
    mean: sum / samples.length,
    count: samples.length,
  };
}

/**
 * Fraction of the theoretically-possible node grid that is actually live in the
 * DOM. Lower is better — it is the whole point of dual-axis virtualization.
 */
export function domEfficiency(renderedNodes: number, totalNodes: number): number {
  if (totalNodes <= 0) return 0;
  return renderedNodes / totalNodes;
}

/** Count of frames that overran the frame budget (default 60fps ≈ 16.7ms). */
export function frameBudgetBreaches(samples: number[], budgetMs = 1000 / 60): number {
  return samples.reduce((n, ms) => (ms > budgetMs ? n + 1 : n), 0);
}

export interface AxisProfile {
  axis: SweepAxis;
  timing: MetricSummary;
  breaches: number;
  peakLiveNodes: number;
}

export interface ProfileResult {
  taskCount: number;
  totalDays: number;
  axes: AxisProfile[];
  /** Peak live nodes ÷ (rows × days), across the whole sweep. */
  domEfficiency: number;
}

/**
 * The impure edge the sweep drives, injected so the attribution logic can be
 * tested without a DOM or a real animation-frame clock.
 */
export interface SweepDriver {
  /** Move the scroll container to a position. */
  seek(top: number, left: number): void;
  /** Resolve on the next painted frame with a monotonic timestamp (ms). */
  nextFrame(): Promise<number>;
  /** Current live-node count reported by the grid. */
  readLiveNodes(): number;
}

/**
 * Drive the plan through the scroll container, measuring the wall-clock cost of
 * the frame each seek triggers and bucketing it by axis.
 *
 * Every phase restarts at the (0,0) origin, so the first frame of each phase
 * measures the *reset* from the previous phase's extreme — a motion along a
 * different axis than the one being entered. That frame is dropped and the
 * baseline re-seeded, so a phase's samples contain only genuine in-phase steps
 * and the reset never contaminates the p95/max/breach stats the sweep isolates.
 */
export async function runDualAxisSweep(
  plan: SweepStop[],
  driver: SweepDriver,
): Promise<AxisProfile[]> {
  const byAxis: Record<SweepAxis, { timings: number[]; peak: number }> = {
    vertical: { timings: [], peak: 0 },
    horizontal: { timings: [], peak: 0 },
    diagonal: { timings: [], peak: 0 },
  };

  let prev = await driver.nextFrame();
  let currentAxis: SweepAxis | null = null;
  for (const stop of plan) {
    driver.seek(stop.top, stop.left);
    // A single frame may not fully absorb react-virtual's async re-render, so
    // some cost can smear onto the next stop — acceptable for a relative sweep.
    const now = await driver.nextFrame();

    if (stop.axis !== currentAxis) {
      currentAxis = stop.axis;
      prev = now;
      continue;
    }

    const bucket = byAxis[stop.axis];
    bucket.timings.push(now - prev);
    bucket.peak = Math.max(bucket.peak, driver.readLiveNodes());
    prev = now;
  }

  return AXES.map((axis) => ({
    axis,
    timing: summarize(byAxis[axis].timings),
    breaches: frameBudgetBreaches(byAxis[axis].timings),
    peakLiveNodes: byAxis[axis].peak,
  }));
}
