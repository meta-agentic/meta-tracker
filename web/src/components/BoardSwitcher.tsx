import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useShallow } from "zustand/react/shallow";
import { useWorkspaceStore } from "../store/workspaceStore";
import { boardIssues, selectBoardList } from "../store/selectors";
import { useNumberFormat } from "../i18n/format";

/**
 * The board bar: which board is on screen, and how much is on it. The totals
 * are derived from the same flat index the board renders from, so they cannot
 * disagree with the columns below them.
 */
export function BoardSwitcher() {
  const { t } = useTranslation();
  const number = useNumberFormat();
  // useShallow is safe here: the sorted array is fresh each call but its
  // elements are the same stable board refs, so a shallow compare bails out.
  const boards = useWorkspaceStore(useShallow(selectBoardList));
  const activeBoardId = useWorkspaceStore((s) => s.activeBoardId);
  const setActiveBoard = useWorkspaceStore((s) => s.setActiveBoard);
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const boardIssueIds = useWorkspaceStore((s) => s.boardIssueIds);

  const totals = useMemo(() => {
    if (!activeBoardId) return null;
    const issues = boardIssues(issuesById, boardIssueIds, activeBoardId);
    const points = issues.reduce((sum, issue) => sum + (issue.storyPoints ?? 0), 0);
    return { items: issues.length, points };
  }, [activeBoardId, issuesById, boardIssueIds]);

  return (
    <div className="vec-boardbar">
      <nav className="vec-boardbar__boards" aria-label={t("boards.label")}>
        {boards.map((board) => (
          <button
            key={board.id}
            type="button"
            className="vec-board-tab"
            aria-current={board.id === activeBoardId ? "true" : undefined}
            onClick={() => setActiveBoard(board.id)}
          >
            {/* Board names are user data, not chrome — deliberately not localized. */}
            {board.name}
          </button>
        ))}
      </nav>
      {totals && (
        <dl className="vec-boardbar__stats">
          <div>
            <dt>{t("board.stats.items")}</dt>
            <dd>{number(totals.items)}</dd>
          </div>
          <div>
            <dt>{t("board.stats.points")}</dt>
            <dd>{number(totals.points)}</dd>
          </div>
        </dl>
      )}
    </div>
  );
}
