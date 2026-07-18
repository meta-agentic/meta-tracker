import { useEffect, useRef } from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import type { Issue } from "../store/types";

const DAY_MS = 86_400_000;

function daysBetween(fromIso: string, toIso: string): number {
  return Math.round(
    (Date.parse(toIso) - Date.parse(fromIso)) / DAY_MS,
  );
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
}

/**
 * Dual-axis virtualization: the vertical virtualizer windows the visible task
 * rows and the horizontal virtualizer windows the visible day columns. Both
 * read the same scroll element, so only rows AND day-columns inside the viewport
 * are ever in the DOM — the layer stays flat (one positioned child per visible
 * row/column), which is what keeps thousands of tasks across multi-year
 * timelines from ballooning the node count.
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
}: TimelineGridProps) {
  const scrollRef = useRef<HTMLDivElement>(null);

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
        border: "1px solid #d4d4d8",
        contain: "strict",
      }}
    >
      <div
        style={{
          height: rowVirtualizer.getTotalSize(),
          width: columnVirtualizer.getTotalSize(),
          position: "relative",
        }}
      >
        {virtualColumns.map((col) => (
          <div
            key={`grid-${col.index}`}
            style={{
              position: "absolute",
              top: 0,
              left: col.start,
              width: 1,
              height: rowVirtualizer.getTotalSize(),
              background: col.index % 7 === 0 ? "#e4e4e7" : "#f4f4f5",
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
                top: row.start,
                left: 0,
                height: rowHeight,
                width: columnVirtualizer.getTotalSize(),
                borderBottom: "1px solid #f4f4f5",
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
                    background: "#4f46e5",
                    color: "#fff",
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
