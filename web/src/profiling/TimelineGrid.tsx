import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useDateFormat } from "../i18n/format";
import type { Issue } from "../store/types";

const DAY_MS = 86_400_000;

/** Height of the sticky day axis, in px. Excluded from the virtualized rows. */
const AXIS_HEIGHT = 22;

function daysBetween(fromIso: string, toIso: string): number {
  return Math.round(
    (Date.parse(toIso) - Date.parse(fromIso)) / DAY_MS,
  );
}

function dayToDate(originIso: string, dayIndex: number): Date {
  return new Date(Date.parse(originIso) + dayIndex * DAY_MS);
}

export interface TimelineGridProps {
  issues: Issue[];
  /** Left edge of the timeline (ISO yyyy-mm-dd). */
  originDate: string;
  totalDays: number;
  dayWidth?: number;
  rowHeight?: number;
  height?: number;
  width?: number;
  /** Reports how many DOM nodes the two virtualizers currently render. */
  onRenderStats?: (stats: { rows: number; columns: number }) => void;
  /** Hands the scroll container to a profiler so it can drive a scripted sweep. */
  onScrollElementReady?: (el: HTMLDivElement | null) => void;
}

/**
 * Dual-axis virtualization: the vertical virtualizer windows the visible task
 * rows and the horizontal virtualizer windows the visible day columns. Both
 * read the same scroll element, so only rows AND day-columns inside the viewport
 * are ever in the DOM — the layer stays flat (one positioned child per visible
 * row/column), which is what keeps thousands of tasks across multi-year
 * timelines from ballooning the node count.
 *
 * The day axis is labelled through the active locale, so a locale swap
 * re-formats the dates in place without disturbing the virtualizers' scroll
 * state. Only week boundaries carry a label — a label per day would be
 * unreadable at a 28px column width and would add a text node to every visible
 * column for nothing.
 */
export function TimelineGrid({
  issues,
  originDate,
  totalDays,
  dayWidth = 28,
  rowHeight = 32,
  height = 600,
  width = 960,
  onRenderStats,
  onScrollElementReady,
}: TimelineGridProps) {
  const { t } = useTranslation();
  const formatDay = useDateFormat({ day: "numeric", month: "short" });
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    onScrollElementReady?.(scrollRef.current);
    return () => onScrollElementReady?.(null);
  }, [onScrollElementReady]);

  const rowVirtualizer = useVirtualizer({
    count: issues.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    overscan: 8,
  });

  const columnVirtualizer = useVirtualizer({
    horizontal: true,
    count: totalDays,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => dayWidth,
    overscan: 8,
  });

  const virtualRows = rowVirtualizer.getVirtualItems();
  const virtualColumns = columnVirtualizer.getVirtualItems();

  const renderedRows = virtualRows.length;
  const renderedColumns = virtualColumns.length;
  useEffect(() => {
    onRenderStats?.({ rows: renderedRows, columns: renderedColumns });
  }, [onRenderStats, renderedRows, renderedColumns]);

  const visibleStartDay = virtualColumns[0]?.index ?? 0;
  const visibleEndDay =
    virtualColumns[virtualColumns.length - 1]?.index ?? totalDays;

  return (
    <div
      ref={scrollRef}
      data-testid="timeline-scroll"
      style={{
        height,
        width,
        overflow: "auto",
        position: "relative",
        border: "1px solid var(--vec-border-strong)",
        background: "var(--vec-surface)",
        contain: "strict",
      }}
    >
      <div
        style={{
          height: rowVirtualizer.getTotalSize() + AXIS_HEIGHT,
          width: columnVirtualizer.getTotalSize(),
          position: "relative",
        }}
      >
        <div
          role="row"
          aria-label={t("timeline.axisLabel")}
          data-testid="timeline-axis"
          style={{
            position: "sticky",
            top: 0,
            left: 0,
            height: AXIS_HEIGHT,
            width: columnVirtualizer.getTotalSize(),
            background: "var(--vec-surface)",
            borderBottom: "1px solid var(--vec-border)",
            color: "var(--vec-text-muted)",
            fontSize: 10,
            zIndex: 1,
          }}
        >
          {virtualColumns
            .filter((col) => col.index % 7 === 0)
            .map((col) => (
              <span
                key={`axis-${col.index}`}
                data-testid="timeline-axis-label"
                style={{
                  position: "absolute",
                  top: 4,
                  left: col.start + 2,
                  whiteSpace: "nowrap",
                }}
              >
                {formatDay(dayToDate(originDate, col.index))}
              </span>
            ))}
        </div>

        {virtualColumns.map((col) => (
          <div
            key={`grid-${col.index}`}
            style={{
              position: "absolute",
              top: AXIS_HEIGHT,
              left: col.start,
              width: 1,
              height: rowVirtualizer.getTotalSize(),
              background:
                col.index % 7 === 0
                  ? "var(--vec-grid-line-major)"
                  : "var(--vec-grid-line)",
            }}
          />
        ))}

        {virtualRows.map((row) => {
          const issue = issues[row.index];
          const startOffset = issue.startDate
            ? daysBetween(originDate, issue.startDate)
            : 0;
          const duration =
            issue.startDate && issue.dueDate
              ? Math.max(1, daysBetween(issue.startDate, issue.dueDate))
              : 1;
          const endOffset = startOffset + duration;
          const intersectsViewport =
            endOffset >= visibleStartDay && startOffset <= visibleEndDay;

          return (
            <div
              key={row.key}
              data-testid="timeline-row"
              style={{
                position: "absolute",
                top: row.start + AXIS_HEIGHT,
                left: 0,
                height: rowHeight,
                width: columnVirtualizer.getTotalSize(),
                borderBottom: "1px solid var(--vec-grid-line)",
              }}
            >
              {intersectsViewport && (
                <div
                  data-testid="timeline-bar"
                  title={`${issue.key} · ${issue.title}`}
                  style={{
                    position: "absolute",
                    top: 4,
                    left: startOffset * dayWidth,
                    width: duration * dayWidth,
                    height: rowHeight - 10,
                    borderRadius: 4,
                    background: "var(--vec-accent)",
                    color: "var(--vec-on-accent)",
                    fontSize: 11,
                    lineHeight: `${rowHeight - 10}px`,
                    paddingLeft: 6,
                    overflow: "hidden",
                    whiteSpace: "nowrap",
                  }}
                >
                  {issue.key}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
