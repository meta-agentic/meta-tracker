import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from "react";
import { useTranslation } from "react-i18next";
import { useVirtualizer } from "@tanstack/react-virtual";
import { useWorkspaceStore } from "../store/workspaceStore";
import { selectFlatTree, type TreeRow } from "../store/selectors";
import { useNumberFormat } from "../i18n/format";
import type { ID } from "../store/types";
import { TypeBadge } from "./IssueCard";
import { ChevronIcon, InboxIcon, ListIcon } from "./icons";
import { EmptyState } from "./states";

export interface BacklogTreeProps {
  boardId: ID;
  /** Height of the scroll viewport. Left unset, the stylesheet sizes it to the window. */
  height?: number | string;
  rowHeight?: number;
  /** Opens an item's detail sheet. Without it, item rows only take focus. */
  onOpenIssue?: (issueId: ID) => void;
  /** Reports the rendered row count and the scroll offset, for the suite. */
  onRenderStats?: (stats: { rows: number; scrollOffset: number }) => void;
}

export interface BacklogTreeHandle {
  /**
   * Focuses the tree, making `id` the active row if the tree currently shows it.
   * For the caller to return focus to when a sheet it opened closes.
   */
  focusRow: (id: ID) => void;
}

function rowDomId(id: ID): string {
  return `backlog-row-${id}`;
}

/**
 * The backlog as a tree: epics with their stories under them, one virtualized
 * list of visible rows.
 *
 * The rows come from `selectFlatTree` already flattened, so this component
 * renders what it is given — it never filters. Expanding or collapsing a row
 * only inserts or removes rows *after* it, and every row has the same height,
 * so nothing above the toggled row moves and the scroll offset is untouched.
 *
 * Keyboard model (WAI-ARIA tree view): the tree is one tab stop and the active
 * row is announced through `aria-activedescendant`, which keeps working when
 * the active row scrolls out of the virtualized window. Up/Down move, Right
 * expands or steps into the first child, Left collapses or steps out to the
 * parent, Home/End jump, Space toggles, and Enter toggles an epic or opens an
 * item in the detail sheet.
 */
export const BacklogTree = forwardRef<BacklogTreeHandle, BacklogTreeProps>(function BacklogTree(
  { boardId, height, rowHeight = 40, onOpenIssue, onRenderStats },
  ref,
) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  const boardsById = useWorkspaceStore((s) => s.boardsById);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);
  const treeExpanded = useWorkspaceStore((s) => s.treeExpanded);
  const toggleTreeRow = useWorkspaceStore((s) => s.toggleTreeRow);

  const expanded = useMemo(() => new Set(treeExpanded), [treeExpanded]);
  const rows = useMemo(
    () => selectFlatTree({ issuesById, epicsById, boardsById, boardIssueIds }, boardId, expanded),
    [issuesById, epicsById, boardsById, boardIssueIds, boardId, expanded],
  );
  const board = boardsById[boardId];
  const columnNames = useMemo(
    () => new Map(board?.columns.map((column) => [column.id, column.name]) ?? []),
    [board],
  );

  // Story points rolled up per epic, over the stories this board shows.
  const epicPoints = useMemo(() => {
    const totals = new Map<ID, number>();
    for (const id of boardIssueIds[boardId] ?? []) {
      const issue = issuesById[id];
      if (issue?.epicId && issue.storyPoints !== null) {
        totals.set(issue.epicId, (totals.get(issue.epicId) ?? 0) + issue.storyPoints);
      }
    }
    return totals;
  }, [issuesById, boardIssueIds, boardId]);

  const scrollRef = useRef<HTMLDivElement>(null);
  const [activeId, setActiveId] = useState<ID | null>(null);
  const activeIndex = Math.max(
    0,
    rows.findIndex((row) => row.id === activeId),
  );
  const active: TreeRow | undefined = rows[activeIndex];

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    getItemKey: (index) => rows[index].id,
    overscan: 8,
  });

  const virtualRows = virtualizer.getVirtualItems();
  const renderedRows = virtualRows.length;
  const scrollOffset = virtualizer.scrollOffset ?? 0;
  useEffect(() => {
    onRenderStats?.({ rows: renderedRows, scrollOffset });
  }, [onRenderStats, renderedRows, scrollOffset]);

  const moveTo = (index: number) => {
    const target = rows[Math.min(Math.max(index, 0), rows.length - 1)];
    if (!target) return;
    setActiveId(target.id);
    virtualizer.scrollToIndex(rows.indexOf(target), { align: "auto" });
  };

  useImperativeHandle(ref, () => ({
    focusRow: (id) => {
      const index = rows.findIndex((row) => row.id === id);
      if (index >= 0) moveTo(index);
      scrollRef.current?.focus();
    },
  }));

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!active) return;
    let handled = true;
    switch (event.key) {
      case "ArrowDown":
        moveTo(activeIndex + 1);
        break;
      case "ArrowUp":
        moveTo(activeIndex - 1);
        break;
      case "Home":
        moveTo(0);
        break;
      case "End":
        moveTo(rows.length - 1);
        break;
      case "ArrowRight":
        if (active.hasChildren && !expanded.has(active.id)) toggleTreeRow(active.id);
        else if (active.hasChildren) moveTo(activeIndex + 1);
        break;
      case "ArrowLeft":
        if (active.hasChildren && expanded.has(active.id)) toggleTreeRow(active.id);
        else if (active.parentId !== null) moveTo(rows.findIndex((row) => row.id === active.parentId));
        break;
      case "Enter":
        if (active.hasChildren) toggleTreeRow(active.id);
        else if (active.kind !== "epic" && onOpenIssue) onOpenIssue(active.id);
        break;
      case " ":
        if (active.hasChildren) toggleTreeRow(active.id);
        break;
      default:
        handled = false;
    }
    if (handled) event.preventDefault();
  };

  if (rows.length === 0) {
    return (
      <EmptyState
        icon={<InboxIcon size={22} />}
        title={t("backlog.empty.title")}
        body={t("backlog.empty.body")}
      />
    );
  }

  return (
    <div
      ref={scrollRef}
      className="vec-tree"
      role="tree"
      aria-label={t("backlog.treeLabel", { board: board?.name ?? "" })}
      aria-activedescendant={active ? rowDomId(active.id) : undefined}
      tabIndex={0}
      onKeyDown={onKeyDown}
      data-testid="backlog-scroll"
      style={height !== undefined ? { height } : undefined}
    >
      <div className="vec-tree__canvas" style={{ height: virtualizer.getTotalSize() }}>
        {virtualRows.map((virtualRow) => {
          const row = rows[virtualRow.index];
          const isEpic = row.kind === "epic";
          const epic = isEpic ? epicsById[row.id] : undefined;
          const issue = isEpic ? undefined : issuesById[row.id];
          const isOpen = expanded.has(row.id);
          const opensSheet = issue !== undefined && onOpenIssue !== undefined;
          const points = isEpic ? (epicPoints.get(row.id) ?? 0) : (issue?.storyPoints ?? null);
          const status = issue ? columnNames.get(issue.columnId) : undefined;

          return (
            <div
              key={virtualRow.key}
              id={rowDomId(row.id)}
              className={isEpic ? "vec-tree__row vec-tree__row--epic" : "vec-tree__row"}
              role="treeitem"
              aria-level={row.depth + 1}
              aria-setsize={row.siblings}
              aria-posinset={row.position}
              aria-expanded={row.hasChildren ? isOpen : undefined}
              aria-selected={active?.id === row.id}
              aria-haspopup={opensSheet ? "dialog" : undefined}
              data-testid="backlog-row"
              data-row-id={row.id}
              data-actionable={row.hasChildren || opensSheet ? "true" : undefined}
              onClick={() => {
                setActiveId(row.id);
                // Focus first, so the sheet's return target is the tree.
                scrollRef.current?.focus();
                if (row.hasChildren) toggleTreeRow(row.id);
                else if (opensSheet) onOpenIssue?.(row.id);
              }}
              style={
                {
                  top: virtualRow.start,
                  height: rowHeight,
                  "--vec-tree-depth": row.depth,
                } as CSSProperties
              }
            >
              <span className="vec-tree__chevron" data-open={isOpen ? "true" : undefined}>
                {row.hasChildren && <ChevronIcon size={14} />}
              </span>
              {epic && (
                // The epic colour is workspace data, so it arrives as a value, not a
                // token. backgroundColor, not background: it can never load a url().
                <span
                  className="vec-tree__swatch"
                  style={{ backgroundColor: epic.color }}
                  aria-hidden="true"
                />
              )}
              {issue && <TypeBadge type={issue.type} compact />}
              <span className="vec-key">{epic?.key ?? issue?.key}</span>
              <span className="vec-tree__title">{epic?.title ?? issue?.title}</span>
              {isEpic && (
                // A count of children, set apart from the points pill by form.
                <span className="vec-tree__count">
                  <ListIcon size={14} />
                  <span aria-hidden="true">{number(row.childCount)}</span>
                  <span className="vec-sr">
                    {t("backlog.childCount", {
                      items: number(row.childCount),
                      count: row.childCount,
                    })}
                  </span>
                </span>
              )}
              {status && (
                // Column names are user data, not chrome — deliberately not localized.
                <span className="vec-chip vec-tree__status">{status}</span>
              )}
              {points !== null && (
                <span className="vec-points">
                  <span aria-hidden="true">{number(points)}</span>
                  <span className="vec-sr">
                    {t("backlog.points", { points: number(points), count: points })}
                  </span>
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
});
