import { describe, expect, it } from "vitest";
import { generateWorkspace } from "./synthetic";

describe("generateWorkspace", () => {
  it("is deterministic for a given seed", () => {
    const a = generateWorkspace({ issues: 50, seed: 42 });
    const b = generateWorkspace({ issues: 50, seed: 42 });
    expect(a.issues).toEqual(b.issues);
  });

  it("spreads schedules across the requested year span", () => {
    const { issues } = generateWorkspace({
      issues: 2_000,
      years: 5,
      startYear: 2024,
      seed: 1,
    });
    const years = new Set(issues.map((i) => i.startDate!.slice(0, 4)));
    expect(years.size).toBeGreaterThan(3);
    expect(issues.every((i) => i.dueDate! >= i.startDate!)).toBe(true);
  });
});
