import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
} from "react";
import { useTranslation } from "react-i18next";
import { defaultRangeExtractor, useVirtualizer, type Range } from "@tanstack/react-virtual";
import { useWorkspaceStore, workspaceStore } from "../store/workspaceStore";
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
 * Near the end of the list a collapse would shorten the tree below the scroll
 * position, and the browser would clamp it and move every row on screen. So a
 * toggle first pins the scrollable height at the viewport's current bottom, a
 * trailing spacer that is released once the rows fill the viewport again.
 *
 * Keyboard model (WAI-ARIA tree view): the tree is one tab stop and the active
 * row is announced through `aria-activedescendant`. The active row is always
 * rendered, even scrolled out of the virtualized window, so the id it names
 * always exists. When the active row stops being visible (its parent was
 * collapsed), the nearest visible ancestor takes over. Up/Down move, Right
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
  // The active row and its ancestors, nearest first: if the row stops being
  // visible, the nearest ancestor still shown is the active one.
  const [activePath, setActivePath] = useState<ID[]>([]);
  const indexById = useMemo(() => new Map(rows.map((row, index) => [row.id, index])), [rows]);
  const activeIndex = Math.max(
    0,
    activePath.map((id) => indexById.get(id)).find((index) => index !== undefined) ?? 0,
  );
  const active: TreeRow | undefined = rows[activeIndex];

  const activate = (row: TreeRow) => {
    const path = [row.id];
    // A visible row's ancestors are all visible, so the walk stays in `rows`.
    for (let parent = row.parentId; parent !== null; ) {
      path.push(parent);
      const index = indexById.get(parent);
      parent = index === undefined ? null : rows[index].parentId;
    }
    setActivePath(path);
  };

  const rangeExtractor = useCallback(
    (range: Range) => {
      const indexes = defaultRangeExtractor(range);
      if (activeIndex >= range.count || indexes.includes(activeIndex)) return indexes;
      return [...indexes, activeIndex].sort((a, b) => a - b);
    },
    [activeIndex],
  );

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    getItemKey: (index) => rows[index].id,
    overscan: 8,
    rangeExtractor,
  });

  // The trailing spacer: a floor under the scrollable height, set by a single
  // row's toggle that would pull the tree's end above the viewport's bottom.
  // It belongs to that one toggle: it holds only while the expansion set and
  // the data are exactly what the toggle left, so any other change — Expand
  // all, Collapse all, another toggle, a refreshed snapshot — drops it and no
  // caller has to remember to release it. A bulk collapse leaves no row above
  // to hold still for, so it lets the browser clamp and bring the remaining
  // rows into view. Scrolling releases it too, once it can no longer clamp.
  const [held, setHeld] = useState<{ bottom: number; expanded: ID[]; issues: object } | null>(
    null,
  );
  const totalSize = virtualizer.getTotalSize();
  const heightFloor =
    held && held.expanded === treeExpanded && held.issues === issuesById && held.bottom > totalSize
      ? held.bottom
      : 0;
  const toggle = (index: number) => {
    const row = rows[index];
    const element = scrollRef.current;
    let bottom = 0;
    if (element && element.scrollTop > 0) {
      // Either direction can end the rows above the viewport's bottom: a
      // collapse removes its visible subtree, and an expand above a held
      // spacer may add fewer rows than the spacer holds. Predicting the new
      // length with the same selector covers both, at any depth.
      const next = new Set(expanded);
      if (next.has(row.id)) next.delete(row.id);
      else next.add(row.id);
      const nextTotal =
        selectFlatTree({ issuesById, epicsById, boardsById, boardIssueIds }, boardId, next).length *
        rowHeight;
      const viewportBottom = element.scrollTop + element.clientHeight;
      if (nextTotal < viewportBottom) bottom = viewportBottom;
    }
    toggleTreeRow(row.id);
    setHeld(
      bottom > 0
        ? { bottom, expanded: workspaceStore.getState().treeExpanded, issues: issuesById }
        : null,
    );
  };
  const onScroll = () => {
    const element = scrollRef.current;
    if (
      held &&
      element &&
      (element.scrollTop === 0 || element.scrollTop + element.clientHeight <= totalSize)
    ) {
      setHeld(null);
    }
  };

  const virtualRows = virtualizer.getVirtualItems();
  const renderedRows = virtualRows.length;
  const scrollOffset = virtualizer.scrollOffset ?? 0;
  useEffect(() => {
    onRenderStats?.({ rows: renderedRows, scrollOffset });
  }, [onRenderStats, renderedRows, scrollOffset]);

  const moveTo = (index: number) => {
    const clamped = Math.min(Math.max(index, 0), rows.length - 1);
    const target = rows[clamped];
    if (!target) return;
    activate(target);
    virtualizer.scrollToIndex(clamped, { align: "auto" });
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
        if (active.hasChildren && !expanded.has(active.id)) toggle(activeIndex);
        else if (active.hasChildren) moveTo(activeIndex + 1);
        break;
      case "ArrowLeft":
        if (active.hasChildren && expanded.has(active.id)) toggle(activeIndex);
        else if (active.parentId !== null) moveTo(rows.findIndex((row) => row.id === active.parentId));
        break;
      case "Enter":
        if (active.hasChildren) toggle(activeIndex);
        else if (active.kind !== "epic" && onOpenIssue) onOpenIssue(active.id);
        break;
      case " ":
        if (active.hasChildren) toggle(activeIndex);
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
      onScroll={onScroll}
      data-testid="backlog-scroll"
      style={height !== undefined ? { height } : undefined}
    >
      <div className="vec-tree__canvas" style={{ height: Math.max(totalSize, heightFloor) }}>
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
                activate(row);
                // Focus first, so the sheet's return target is the tree.
                scrollRef.current?.focus();
                if (row.hasChildren) toggle(virtualRow.index);
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
