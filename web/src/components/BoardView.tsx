import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useWorkspaceStore } from "../store/workspaceStore";
import { boardColumns, selectActiveBoard } from "../store/selectors";
import { useNumberFormat } from "../i18n/format";
import type { ID } from "../store/types";
import { IssueCard } from "./IssueCard";
import { BoardIcon } from "./icons";
import { EmptyState } from "./states";

/** Cards rendered per column before the rest collapse into a "+N more" line. */
const COLUMN_CARD_LIMIT = 50;

export interface BoardViewProps {
  onOpenIssue: (issueId: ID) => void;
}

/**
 * Renders the active board from the flat store. Because every board's data is
 * already normalized in memory and cache-hydrated, switching boards only swaps
 * `activeBoardId` — this component recomputes from O(1) maps with no fetch and
 * no loading state.
 *
 * The grouping is derived in a `useMemo` over stable store slices rather than in
 * the zustand selector itself: a selector that allocated a fresh object on every
 * call would defeat `useSyncExternalStore`'s snapshot caching and loop.
 */
export function BoardView({ onOpenIssue }: BoardViewProps) {
  const { t } = useTranslation();
  const number = useNumberFormat();
  const board = useWorkspaceStore(selectActiveBoard);
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);

  const columns = useMemo(
    () => (board ? boardColumns(issuesById, boardIssueIds, board.id) : {}),
    [board, issuesById, boardIssueIds],
  );

  if (!board) return <p className="vec-muted-note">{t("board.empty")}</p>;

  if (board.columns.length === 0) {
    return (
      <EmptyState
        icon={<BoardIcon size={22} />}
        title={t("board.noColumns.title")}
        body={t("board.noColumns.body")}
      />
    );
  }

  return (
    <div className="vec-board">
      {board.columns.map((column) => {
        const issues = columns[column.id] ?? [];
        const headingId = `vec-column-${column.id}`;
        return (
          <section key={column.id} className="vec-column" aria-labelledby={headingId}>
            <div className="vec-column__header">
              <h2 id={headingId} className="vec-column__name">
                {/* Column names are user data, not chrome — deliberately not localized. */}
                {column.name}
              </h2>
              <span className="vec-count">
                {t("board.columnCount", { count: number(issues.length) })}
              </span>
            </div>
            <div className="vec-column__body">
              {issues.length === 0 && (
                <p className="vec-column__empty">{t("board.columnEmpty")}</p>
              )}
              {issues.slice(0, COLUMN_CARD_LIMIT).map((issue) => (
                <IssueCard
                  key={issue.id}
                  issue={issue}
                  epic={issue.epicId ? (epicsById[issue.epicId] ?? null) : null}
                  onOpen={onOpenIssue}
                />
              ))}
              {issues.length > COLUMN_CARD_LIMIT && (
                <p className="vec-column__more">
                  {t("board.moreIssues", {
                    count: number(issues.length - COLUMN_CARD_LIMIT),
                  })}
                </p>
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}
