import { useMemo, useState } from "react";
import { generateWorkspace } from "../lib/synthetic";
import { TimelineGrid } from "./TimelineGrid";

const ORIGIN = "2024-01-01";

interface Scenario {
  issues: number;
  years: number;
}

const PRESETS: Scenario[] = [
  { issues: 1_000, years: 2 },
  { issues: 10_000, years: 3 },
  { issues: 50_000, years: 5 },
  { issues: 100_000, years: 8 },
];

/**
 * VEC-21 spike surface: generate N synthetic tasks across an M-year timeline and
 * render them through the dual-axis virtualizer, reporting how many DOM nodes are
 * actually live versus the total task count.
 */
export function ProfilingHarness() {
  const [scenario, setScenario] = useState<Scenario>(PRESETS[1]);
  const [stats, setStats] = useState({ rows: 0, columns: 0 });

  const { snapshot, genMs } = useMemo(() => {
    const t0 = performance.now();
    const snap = generateWorkspace({
      boards: 1,
      epics: 24,
      issues: scenario.issues,
      years: scenario.years,
      startYear: 2024,
    });
    return { snapshot: snap, genMs: performance.now() - t0 };
  }, [scenario]);

  const totalDays = scenario.years * 365;
  const liveNodes = stats.rows + stats.columns;

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: "flex", gap: 8, marginBottom: 12, flexWrap: "wrap" }}>
        {PRESETS.map((preset) => (
          <button
            key={`${preset.issues}-${preset.years}`}
            onClick={() => setScenario(preset)}
            style={{
              padding: "4px 10px",
              borderRadius: 6,
              border: "1px solid #d4d4d8",
              background: preset === scenario ? "#4f46e5" : "#fff",
              color: preset === scenario ? "#fff" : "#18181b",
              cursor: "pointer",
              fontSize: 12,
            }}
          >
            {preset.issues.toLocaleString()} tasks · {preset.years}y
          </button>
        ))}
      </div>

      <dl
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(4, max-content)",
          gap: "2px 24px",
          fontSize: 13,
          marginBottom: 12,
        }}
      >
        <dt>Total tasks</dt>
        <dd>{scenario.issues.toLocaleString()}</dd>
        <dt>Timeline span</dt>
        <dd>{totalDays.toLocaleString()} days</dd>
        <dt>Live DOM nodes</dt>
        <dd>
          {liveNodes} ({stats.rows} rows × {stats.columns} cols)
        </dd>
        <dt>Generation</dt>
        <dd>{genMs.toFixed(1)} ms</dd>
      </dl>

      <TimelineGrid
        issues={snapshot.issues}
        originDate={ORIGIN}
        totalDays={totalDays}
        onRenderStats={setStats}
      />
    </div>
  );
}
