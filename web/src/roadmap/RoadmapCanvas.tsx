import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
} from "react";
import { useTranslation } from "react-i18next";
import { defaultRangeExtractor, useVirtualizer, type Range } from "@tanstack/react-virtual";
import { useWorkspaceStore } from "../store/workspaceStore";
import { boardIssues, selectFlatTree } from "../store/selectors";
import { useDateFormat } from "../i18n/format";
import type { ID } from "../store/types";
import { ChevronIcon } from "../components/icons";
import {
  addDays,
  barSpan,
  daysBetween,
  edgeDates,
  moveEdge,
  timelineRange,
  unionSpan,
  type BarEdge,
  type BarSpan,
} from "./roadmapModel";
import { BarHandle } from "./BarHandle";

/** Height of the sticky header row: the day axis and, above the ledger, its caption. */
export const AXIS_HEIGHT = 28;

export interface RoadmapCanvasProps {
  boardId: ID;
  rowHeight?: number;
  dayWidth?: number;
  ledgerWidth?: number;
  /** Height of the scroll viewport. Left unset, the stylesheet sizes it to the window. */
  height?: number | string;
  /** Reports how many rows and day columns the shared virtualizers render, for the suite. */
  onRenderStats?: (stats: { rows: number; columns: number }) => void;
}

interface Drag {
  issueId: ID;
  edge: BarEdge;
  pointerX: number;
  span: BarSpan;
  delta: number;
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * The roadmap: the backlog tree as a ledger on the left, its items as Gantt
 * bars on the right, one row each.
 *
 * Both panes live in ONE scroll element and are laid out by ONE row
 * virtualizer, so a ledger row and its bar row are the same virtual item placed
 * at the same offset — they cannot drift apart, because there is no second
 * scroll position to keep in step. The ledger is sticky on the left, so the
 * horizontal scroll moves only the Gantt pane; a second, horizontal virtualizer
 * windows the day columns as `TimelineGrid` does.
 *
 * The rows are `selectFlatTree`'s, under the same persisted expansion set as the
 * backlog tab, so collapsing an epic here collapses it there too.
 *
 * Dragging either end of a bar, or pressing an arrow key on it, writes the new
 * date back to the item's `startDate` or `dueDate` in the store.
 */
export function RoadmapCanvas({
  boardId,
  rowHeight = 36,
  dayWidth = 24,
  ledgerWidth = 320,
  height,
  onRenderStats,
}: RoadmapCanvasProps) {
  const { t } = useTranslation();
  const formatDay = useDateFormat({ day: "numeric", month: "short" });
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  const boardsById = useWorkspaceStore((s) => s.boardsById);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);
  const treeExpanded = useWorkspaceStore((s) => s.treeExpanded);
  const toggleTreeRow = useWorkspaceStore((s) => s.toggleTreeRow);
  const upsertIssue = useWorkspaceStore((s) => s.upsertIssue);

  const expanded = useMemo(() => new Set(treeExpanded), [treeExpanded]);
  const rows = useMemo(
    () => selectFlatTree({ issuesById, epicsById, boardsById, boardIssueIds }, boardId, expanded),
    [issuesById, epicsById, boardsById, boardIssueIds, boardId, expanded],
  );

  const issues = useMemo(
    () => boardIssues(issuesById, boardIssueIds, boardId),
    [issuesById, boardIssueIds, boardId],
  );
  const [fallbackDate] = useState(todayIso);
  const { originDate, totalDays } = useMemo(
    () => timelineRange(issues, fallbackDate),
    [issues, fallbackDate],
  );
  const epicSpans = useMemo(() => {
    const byEpic = new Map<ID, (BarSpan | null)[]>();
    for (const issue of issues) {
      if (!issue.epicId) continue;
      const spans = byEpic.get(issue.epicId) ?? [];
      spans.push(barSpan(issue, originDate));
      byEpic.set(issue.epicId, spans);
    }
    return new Map([...byEpic].map(([id, spans]) => [id, unionSpan(spans)]));
  }, [issues, originDate]);

  const scrollRef = useRef<HTMLDivElement>(null);
  const [drag, setDragState] = useState<Drag | null>(null);
  // Mirrors `drag` for the pointer-up handler: a release can arrive before
  // React has rendered the last move, and the closure would commit a stale delta.
  const dragRef = useRef<Drag | null>(null);
  const setDrag = (next: Drag | null) => {
    dragRef.current = next;
    setDragState(next);
  };
  const dragIndex = drag ? rows.findIndex((row) => row.id === drag.issueId) : -1;

  // The dragged row stays mounted while it is scrolled out of the window, or
  // the handle holding the pointer capture would unmount mid-gesture.
  const rangeExtractor = useCallback(
    (range: Range) => {
      const indexes = defaultRangeExtractor(range);
      if (dragIndex < 0 || dragIndex >= range.count || indexes.includes(dragIndex)) return indexes;
      return [...indexes, dragIndex].sort((a, b) => a - b);
    },
    [dragIndex],
  );

  const rowVirtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    getItemKey: (index) => rows[index].id,
    overscan: 8,
    rangeExtractor,
  });
  const columnVirtualizer = useVirtualizer({
    horizontal: true,
    count: totalDays,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => dayWidth,
    overscan: 8,
  });

  // A drag past the earliest date moves the origin left, which shifts every bar
  // right by the same amount. Scrolling right by it too keeps the canvas still
  // under the pointer.
  const originRef = useRef(originDate);
  useLayoutEffect(() => {
    const shift = daysBetween(originDate, originRef.current);
    originRef.current = originDate;
    const element = scrollRef.current;
    if (shift !== 0 && element) element.scrollLeft += shift * dayWidth;
  }, [originDate, dayWidth]);

  const virtualRows = rowVirtualizer.getVirtualItems();
  const virtualColumns = columnVirtualizer.getVirtualItems();
  const rowsHeight = rowVirtualizer.getTotalSize();
  const daysWidth = columnVirtualizer.getTotalSize();
  const visibleStartDay = virtualColumns[0]?.index ?? 0;
  const visibleEndDay = virtualColumns[virtualColumns.length - 1]?.index ?? totalDays;

  const renderedRows = virtualRows.length;
  const renderedColumns = virtualColumns.length;
  useEffect(() => {
    onRenderStats?.({ rows: renderedRows, columns: renderedColumns });
  }, [onRenderStats, renderedRows, renderedColumns]);

  const commit = (issueId: ID, edge: BarEdge, span: BarSpan) => {
    const issue = issuesById[issueId];
    if (!issue) return;
    const dates = edgeDates(issue, span, edge, originDate);
    if (dates.startDate === issue.startDate && dates.dueDate === issue.dueDate) return;
    upsertIssue({ ...issue, ...dates });
  };

  const board = boardsById[boardId];
  const dayLabel = (day: number) => formatDay(new Date(Date.parse(addDays(originDate, day))));

  return (
    <div
      ref={scrollRef}
      className="vec-roadmap"
      data-testid="roadmap-scroll"
      aria-label={t("roadmap.label", { board: board?.name ?? "" })}
      role="region"
      style={height !== undefined ? { height } : undefined}
    >
      <div
        className="vec-roadmap__canvas"
        style={{ width: ledgerWidth + daysWidth, height: AXIS_HEIGHT + rowsHeight }}
      >
        <div className="vec-roadmap__header" style={{ height: AXIS_HEIGHT }}>
          <div className="vec-roadmap__corner" style={{ width: ledgerWidth }}>
            {t("roadmap.ledgerCaption")}
          </div>
          <div
            className="vec-roadmap__axis"
            aria-label={t("timeline.axisLabel")}
            data-testid="roadmap-axis"
            style={{ width: daysWidth }}
          >
            {virtualColumns
              .filter((col) => col.index % 7 === 0)
              .map((col) => (
                <span key={col.index} className="vec-roadmap__axis-label" style={{ left: col.start }}>
                  {dayLabel(col.index)}
                </span>
              ))}
          </div>
        </div>

        <div className="vec-roadmap__body" style={{ height: rowsHeight }}>
          <div
            className="vec-roadmap__ledger"
            data-testid="roadmap-ledger"
            style={{ width: ledgerWidth, height: rowsHeight }}
          >
            {virtualRows.map((virtualRow) => {
              const row = rows[virtualRow.index];
              const epic = row.kind === "epic" ? epicsById[row.id] : undefined;
              const issue = epic ? undefined : issuesById[row.id];
              const isOpen = expanded.has(row.id);
              const key = epic?.key ?? issue?.key ?? "";
              return (
                <div
                  key={virtualRow.key}
                  className={epic ? "vec-roadmap__lrow vec-roadmap__lrow--epic" : "vec-roadmap__lrow"}
                  data-testid="roadmap-ledger-row"
                  data-row-id={row.id}
                  style={
                    {
                      top: virtualRow.start,
                      height: rowHeight,
                      "--vec-tree-depth": row.depth,
                    } as CSSProperties
                  }
                >
                  {row.hasChildren ? (
                    <button
                      type="button"
                      className="vec-roadmap__toggle"
                      aria-expanded={isOpen}
                      aria-label={t("roadmap.toggle", { key })}
                      onClick={() => toggleTreeRow(row.id)}
                    >
                      <span className="vec-tree__chevron" data-open={isOpen ? "true" : undefined}>
                        <ChevronIcon size={14} />
                      </span>
                    </button>
                  ) : (
                    <span className="vec-roadmap__toggle" aria-hidden="true" />
                  )}
                  {epic && (
                    <span
                      className="vec-tree__swatch"
                      style={{ backgroundColor: epic.color }}
                      aria-hidden="true"
                    />
                  )}
                  <span className="vec-key">{key}</span>
                  <span className="vec-tree__title">{epic?.title ?? issue?.title}</span>
                </div>
              );
            })}
          </div>

          <div className="vec-roadmap__gantt" style={{ width: daysWidth, height: rowsHeight }}>
            {virtualColumns.map((col) => (
              <div
                key={col.index}
                className={
                  col.index % 7 === 0
                    ? "vec-roadmap__gridline vec-roadmap__gridline--major"
                    : "vec-roadmap__gridline"
                }
                style={{ left: col.start }}
              />
            ))}

            {virtualRows.map((virtualRow) => {
              const row = rows[virtualRow.index];
              const epic = row.kind === "epic" ? epicsById[row.id] : undefined;
              const issue = epic ? undefined : issuesById[row.id];
              const dragging = drag !== null && drag.issueId === row.id;
              const stored = epic
                ? (epicSpans.get(row.id) ?? null)
                : issue
                  ? barSpan(issue, originDate)
                  : null;
              const span = dragging ? moveEdge(drag.span, drag.edge, drag.delta, totalDays) : stored;
              const visible =
                span !== null && (dragging || (span.end >= visibleStartDay && span.start <= visibleEndDay));

              return (
                <div
                  key={virtualRow.key}
                  className="vec-roadmap__grow"
                  data-testid="roadmap-gantt-row"
                  data-row-id={row.id}
                  style={{ top: virtualRow.start, height: rowHeight }}
                >
                  {visible && span && epic && (
                    <div
                      className="vec-roadmap__bar vec-roadmap__bar--summary"
                      data-testid="roadmap-bar"
                      data-bar-id={row.id}
                      title={`${epic.key} · ${epic.title}`}
                      style={{
                        left: span.start * dayWidth,
                        width: (span.end - span.start) * dayWidth,
                        backgroundColor: epic.color,
                      }}
                    />
                  )}
                  {visible && span && issue && (
                    <div
                      className="vec-roadmap__bar"
                      data-testid="roadmap-bar"
                      data-bar-id={row.id}
                      data-dragging={dragging ? "true" : undefined}
                      title={`${issue.key} · ${issue.title}`}
                      style={{
                        left: span.start * dayWidth,
                        width: (span.end - span.start) * dayWidth,
                      }}
                    >
                      <span className="vec-roadmap__bar-label">{issue.key}</span>
                      {(["start", "end"] as const).map((edge) => (
                        <BarHandle
                          key={edge}
                          edge={edge}
                          label={
                            edge === "start"
                              ? t("roadmap.startHandle", { key: issue.key })
                              : t("roadmap.endHandle", { key: issue.key })
                          }
                          day={edge === "start" ? span.start : span.end}
                          totalDays={totalDays}
                          valueText={dayLabel(edge === "start" ? span.start : span.end)}
                          onDragStart={(pointerX) =>
                            setDrag({ issueId: issue.id, edge, pointerX, span, delta: 0 })
                          }
                          onDragMove={(pointerX) => {
                            const current = dragRef.current;
                            if (!current) return;
                            const delta = Math.round((pointerX - current.pointerX) / dayWidth);
                            if (delta !== current.delta) setDrag({ ...current, delta });
                          }}
                          onDragEnd={(cancelled) => {
                            const current = dragRef.current;
                            if (current && !cancelled) {
                              commit(
                                current.issueId,
                                current.edge,
                                moveEdge(current.span, current.edge, current.delta, totalDays),
                              );
                            }
                            setDrag(null);
                          }}
                          onStep={(days) => commit(issue.id, edge, moveEdge(span, edge, days, totalDays))}
                        />
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
