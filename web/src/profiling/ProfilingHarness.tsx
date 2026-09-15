import { useCallback, useMemo, useRef, useState } from "react";
import { generateWorkspace } from "../lib/synthetic";
import { TimelineGrid } from "./TimelineGrid";
import {
  domEfficiency,
  planDualAxisSweep,
  runDualAxisSweep,
  type ProfileResult,
  type SweepDriver,
} from "./profiler";

const ORIGIN = "2024-01-01";
const GRID = { dayWidth: 28, rowHeight: 32, height: 600, width: 960 };
const STEPS_PER_AXIS = 12;

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

function nextFrame(): Promise<number> {
  return new Promise((resolve) => requestAnimationFrame((t) => resolve(t)));
}

/**
 * VEC-21 dual-axis profiling surface. Generates N synthetic tasks across an
 * M-year timeline, renders them through the dual-axis virtualizer, and can run a
 * scripted scroll sweep that isolates the vertical (task) and horizontal (day)
 * axes to attribute frame cost to whichever axis actually dominates.
 */
export function ProfilingHarness() {
  const [scenario, setScenario] = useState<Scenario>(PRESETS[1]);
  const [stats, setStats] = useState({ rows: 0, columns: 0 });
  const [result, setResult] = useState<ProfileResult | null>(null);
  const [running, setRunning] = useState(false);

  const scrollElRef = useRef<HTMLDivElement | null>(null);
  const liveNodesRef = useRef(0);

  /*
   * Measuring the generation cost is the point of this harness, and the work
   * being timed is the useMemo body itself, so the clock has to be read where
   * the work happens — reading it outside the memo would measure something
   * else. `genMs` is display-only: its single consumer is the <dd> below, and
   * no branch, hook dependency or memo key reads it. A value that moves between
   * re-renders therefore changes a displayed diagnostic and nothing else.
   */
  /* eslint-disable react-hooks/purity -- deliberate instrumentation, see above */
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
  /* eslint-enable react-hooks/purity */

  const totalDays = scenario.years * 365;
  const liveNodes = stats.rows + stats.columns;

  const handleStats = useCallback((s: { rows: number; columns: number }) => {
    liveNodesRef.current = s.rows + s.columns;
    setStats(s);
  }, []);

  const handleScrollEl = useCallback((el: HTMLDivElement | null) => {
    scrollElRef.current = el;
  }, []);

  const runProfile = useCallback(async () => {
    const el = scrollElRef.current;
    if (!el || running) return;
    setRunning(true);
    try {
      const plan = planDualAxisSweep(
        { scrollSize: snapshot.issues.length * GRID.rowHeight, viewport: GRID.height },
        { scrollSize: totalDays * GRID.dayWidth, viewport: GRID.width },
        STEPS_PER_AXIS,
      );
      const driver: SweepDriver = {
        seek: (top, left) => {
          el.scrollTop = top;
          el.scrollLeft = left;
        },
        nextFrame,
        readLiveNodes: () => liveNodesRef.current,
      };
      const axes = await runDualAxisSweep(plan, driver);
      el.scrollTop = 0;
      el.scrollLeft = 0;
      const peak = Math.max(...axes.map((a) => a.peakLiveNodes));
      setResult({
        taskCount: snapshot.issues.length,
        totalDays,
        axes,
        domEfficiency: domEfficiency(peak, snapshot.issues.length * totalDays),
      });
    } finally {
      setRunning(false);
    }
  }, [running, snapshot.issues.length, totalDays]);

  return (
    <div style={{ padding: 16 }}>
      <div style={{ display: "flex", gap: 8, marginBottom: 12, flexWrap: "wrap" }}>
        {PRESETS.map((preset) => (
          <button
            key={`${preset.issues}-${preset.years}`}
            onClick={() => {
              setScenario(preset);
              setResult(null);
            }}
            style={presetStyle(preset === scenario)}
          >
            {preset.issues.toLocaleString()} tasks · {preset.years}y
          </button>
        ))}
        <button
          onClick={runProfile}
          disabled={running}
          style={{ ...presetStyle(false), marginLeft: "auto", fontWeight: 600 }}
        >
          {running ? "Profiling…" : "Run dual-axis profile"}
        </button>
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

      {result && <ProfileTable result={result} />}

      <TimelineGrid
        issues={snapshot.issues}
        originDate={ORIGIN}
        totalDays={totalDays}
        dayWidth={GRID.dayWidth}
        rowHeight={GRID.rowHeight}
        height={GRID.height}
        width={GRID.width}
        onRenderStats={handleStats}
        onScrollElementReady={handleScrollEl}
      />
    </div>
  );
}

function ProfileTable({ result }: { result: ProfileResult }) {
  return (
    <div style={{ marginBottom: 12, fontSize: 12 }}>
      <table style={{ borderCollapse: "collapse", minWidth: 520 }}>
        <thead>
          <tr style={{ textAlign: "left", color: "#71717a" }}>
            <th style={cell}>Axis</th>
            <th style={cell}>p50 (ms)</th>
            <th style={cell}>p95 (ms)</th>
            <th style={cell}>max (ms)</th>
            <th style={cell}>&gt;16.7ms</th>
            <th style={cell}>peak nodes</th>
          </tr>
        </thead>
        <tbody>
          {result.axes.map((a) => (
            <tr key={a.axis}>
              <td style={cell}>{a.axis}</td>
              <td style={cell}>{a.timing.p50.toFixed(1)}</td>
              <td style={cell}>{a.timing.p95.toFixed(1)}</td>
              <td style={cell}>{a.timing.max.toFixed(1)}</td>
              <td style={cell}>
                {a.breaches}/{a.timing.count}
              </td>
              <td style={cell}>{a.peakLiveNodes}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p style={{ color: "#71717a", marginTop: 6 }}>
        DOM efficiency: {(result.domEfficiency * 100).toFixed(4)}% of the{" "}
        {(result.taskCount * result.totalDays).toLocaleString()}-cell grid was ever
        live.
      </p>
    </div>
  );
}

const cell: React.CSSProperties = {
  border: "1px solid #e4e4e7",
  padding: "4px 8px",
};

function presetStyle(active: boolean): React.CSSProperties {
  return {
    padding: "4px 10px",
    borderRadius: 6,
    border: "1px solid #d4d4d8",
    background: active ? "#4f46e5" : "#fff",
    color: active ? "#fff" : "#18181b",
    cursor: "pointer",
    fontSize: 12,
  };
}
