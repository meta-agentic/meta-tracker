import { useCallback, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { generateWorkspace } from "../lib/synthetic";
import { useNumberFormat } from "../i18n/format";
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
 * Dual-axis profiling surface. Generates N synthetic tasks across an
 * M-year timeline, renders them through the dual-axis virtualizer, and can run a
 * scripted scroll sweep that isolates the vertical (task) and horizontal (day)
 * axes to attribute frame cost to whichever axis actually dominates.
 */
export function ProfilingHarness() {
  const { t } = useTranslation();
  const number = useNumberFormat();
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
            {t("profiler.preset", {
              tasks: number(preset.issues),
              years: preset.years,
            })}
          </button>
        ))}
        <button
          onClick={runProfile}
          disabled={running}
          style={{ ...presetStyle(false), marginLeft: "auto", fontWeight: 600 }}
        >
          {running ? t("profiler.running") : t("profiler.run")}
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
        <dt>{t("profiler.totalTasks")}</dt>
        <dd>{number(scenario.issues)}</dd>
        <dt>{t("profiler.timelineSpan")}</dt>
        <dd>{t("profiler.days", { days: number(totalDays) })}</dd>
        <dt>{t("profiler.liveNodes")}</dt>
        <dd>
          {t("profiler.liveNodesDetail", {
            total: number(liveNodes),
            rows: number(stats.rows),
            columns: number(stats.columns),
          })}
        </dd>
        <dt>{t("profiler.generation")}</dt>
        <dd>{t("profiler.milliseconds", { value: number(genMs) })}</dd>
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
  const { t } = useTranslation();
  // One fraction digit for milliseconds, matching the previous `toFixed(1)` —
  // but rendered through the locale, so a comma decimal separator appears where
  // the locale uses one.
  const ms = useNumberFormat({
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  });
  const count = useNumberFormat();
  const percent = useNumberFormat({
    minimumFractionDigits: 4,
    maximumFractionDigits: 4,
  });

  return (
    <div style={{ marginBottom: 12, fontSize: 12 }}>
      <table style={{ borderCollapse: "collapse", minWidth: 520 }}>
        <thead>
          <tr style={{ textAlign: "left", color: "var(--vec-text-muted)" }}>
            <th style={cell}>{t("profiler.table.axis")}</th>
            <th style={cell}>{t("profiler.table.p50")}</th>
            <th style={cell}>{t("profiler.table.p95")}</th>
            <th style={cell}>{t("profiler.table.max")}</th>
            <th style={cell}>{t("profiler.table.breaches")}</th>
            <th style={cell}>{t("profiler.table.peakNodes")}</th>
          </tr>
        </thead>
        <tbody>
          {result.axes.map((a) => (
            <tr key={a.axis}>
              <td style={cell}>{t(`profiler.axis.${a.axis}`)}</td>
              <td style={cell}>{ms(a.timing.p50)}</td>
              <td style={cell}>{ms(a.timing.p95)}</td>
              <td style={cell}>{ms(a.timing.max)}</td>
              <td style={cell}>
                {t("profiler.table.breachRatio", {
                  breaches: count(a.breaches),
                  total: count(a.timing.count),
                })}
              </td>
              <td style={cell}>{count(a.peakLiveNodes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p style={{ color: "var(--vec-text-muted)", marginTop: 6 }}>
        {t("profiler.domEfficiency", {
          percent: percent(result.domEfficiency * 100),
          cells: count(result.taskCount * result.totalDays),
        })}
      </p>
    </div>
  );
}

const cell: React.CSSProperties = {
  border: "1px solid var(--vec-border)",
  padding: "4px 8px",
};

function presetStyle(active: boolean): React.CSSProperties {
  return {
    padding: "4px 10px",
    borderRadius: 6,
    border: "1px solid var(--vec-border-strong)",
    background: active ? "var(--vec-accent)" : "var(--vec-surface)",
    color: active ? "var(--vec-on-accent)" : "var(--vec-text)",
    cursor: "pointer",
    fontSize: 12,
  };
}
