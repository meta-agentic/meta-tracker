import { describe, expect, it } from "vitest";
import {
  domEfficiency,
  frameBudgetBreaches,
  percentile,
  planDualAxisSweep,
  runDualAxisSweep,
  summarize,
  type SweepDriver,
} from "./profiler";

function scriptedDriver(timestamps: number[]): SweepDriver {
  let i = 0;
  return {
    seek: () => {},
    nextFrame: () => Promise.resolve(timestamps[i++]),
    readLiveNodes: () => 10,
  };
}

describe("planDualAxisSweep", () => {
  it("sweeps each axis in isolation then diagonally", () => {
    const stops = planDualAxisSweep(
      { scrollSize: 1000, viewport: 200 },
      { scrollSize: 500, viewport: 100 },
      4,
    );
    // 3 phases × (4 + 1) stops
    expect(stops).toHaveLength(15);

    const vertical = stops.filter((s) => s.axis === "vertical");
    const horizontal = stops.filter((s) => s.axis === "horizontal");
    const diagonal = stops.filter((s) => s.axis === "diagonal");

    // vertical phase never moves the horizontal axis, and vice versa
    expect(vertical.every((s) => s.left === 0)).toBe(true);
    expect(horizontal.every((s) => s.top === 0)).toBe(true);
    // diagonal moves both to their extremes at fraction 1
    expect(diagonal.at(-1)).toMatchObject({ top: 800, left: 400 });
    // extremes are clamped to scrollSize - viewport
    expect(vertical.at(-1)!.top).toBe(800);
    expect(horizontal.at(-1)!.left).toBe(400);
  });

  it("clamps negative scroll ranges to zero", () => {
    const stops = planDualAxisSweep(
      { scrollSize: 100, viewport: 400 },
      { scrollSize: 100, viewport: 400 },
      2,
    );
    expect(stops.every((s) => s.top === 0 && s.left === 0)).toBe(true);
  });
});

describe("percentile / summarize", () => {
  it("interpolates percentiles", () => {
    const s = [10, 20, 30, 40, 50];
    expect(percentile(s, 0)).toBe(10);
    expect(percentile(s, 50)).toBe(30);
    expect(percentile(s, 100)).toBe(50);
    expect(percentile(s, 75)).toBe(40);
  });

  it("summarizes an empty set to zeros", () => {
    expect(summarize([])).toEqual({ p50: 0, p95: 0, max: 0, mean: 0, count: 0 });
  });

  it("summarizes frame samples", () => {
    const s = summarize([8, 12, 16, 40]);
    expect(s.count).toBe(4);
    expect(s.max).toBe(40);
    expect(s.mean).toBe(19);
  });
});

describe("runDualAxisSweep", () => {
  it("drops phase-boundary reset frames so cost is not misattributed across axes", async () => {
    const plan = planDualAxisSweep(
      { scrollSize: 1000, viewport: 200 },
      { scrollSize: 1000, viewport: 200 },
      2,
    );
    // baseline + 9 stops. The three phase-origin frames (indices 0, 3, 6) are
    // expensive resets from the prior phase's extreme back to (0,0), along a
    // different axis than the phase being entered — they must NOT be bucketed.
    const driver = scriptedDriver([
      0, 100, 105, 111, 211, 216, 222, 322, 327, 333,
    ]);
    const axes = await runDualAxisSweep(plan, driver);

    for (const axis of axes) {
      expect(axis.timing.count).toBe(2); // origin frame dropped, 2 in-phase steps kept
      expect(axis.timing.max).toBe(6); // only the 5/6ms steps, never the 100ms reset
      expect(axis.breaches).toBe(0);
    }
  });
});

describe("domEfficiency / frameBudgetBreaches", () => {
  it("is the live-to-total node ratio, guarding empty grids", () => {
    expect(domEfficiency(120, 60000)).toBeCloseTo(0.002);
    expect(domEfficiency(5, 0)).toBe(0);
  });

  it("counts frames over the budget", () => {
    expect(frameBudgetBreaches([8, 20, 12, 33], 16.7)).toBe(2);
  });
});
