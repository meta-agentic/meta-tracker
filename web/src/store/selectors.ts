import type { Board, ID, Issue } from "./types";
import type { WorkspaceState } from "./workspaceStore";

/** Ordered issues for a board, assembled from the flat index — O(k) in the
 * board's issue count, never a scan of the whole workspace. */
export function boardIssues(
  issuesById: Record<ID, Issue>,
  boardIssueIds: Record<ID, ID[]>,
  boardId: ID,
): Issue[] {
  const ids = boardIssueIds[boardId];
  if (!ids) return [];
  const out: Issue[] = [];
  for (const id of ids) {
    const issue = issuesById[id];
    if (issue) out.push(issue);
  }
  return out;
}

/** Issues grouped by column id for a board, each column ordered by `order`. */
export function boardColumns(
  issuesById: Record<ID, Issue>,
  boardIssueIds: Record<ID, ID[]>,
  boardId: ID,
): Record<ID, Issue[]> {
  const grouped: Record<ID, Issue[]> = {};
  for (const issue of boardIssues(issuesById, boardIssueIds, boardId)) {
    (grouped[issue.columnId] ??= []).push(issue);
  }
  for (const column of Object.values(grouped)) {
    column.sort((a, b) => a.order - b.order);
  }
  return grouped;
}

export function selectActiveBoard(state: WorkspaceState): Board | null {
  return state.activeBoardId ? state.boardsById[state.activeBoardId] ?? null : null;
}

export function selectBoardIssues(state: WorkspaceState, boardId: ID): Issue[] {
  return boardIssues(state.issuesById, state.boardIssueIds, boardId);
}

export function selectBoardColumns(
  state: WorkspaceState,
  boardId: ID,
): Record<ID, Issue[]> {
  return boardColumns(state.issuesById, state.boardIssueIds, boardId);
}

export function selectBoardList(state: WorkspaceState): Board[] {
  return Object.values(state.boardsById).sort((a, b) =>
    a.name.localeCompare(b.name),
  );
}
