import type { Board, ID, Issue } from "./types";
import type { WorkspaceState } from "./workspaceStore";
import { dict } from "./dict";

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
  const grouped = dict<Issue[]>();
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

/* ---------- Backlog tree ---------- */

/** One visible row of a flattened tree. */
export interface TreeRow {
  id: ID;
  /** The level the row belongs to — "epic", "issue", or whatever a level names itself. */
  kind: string;
  depth: number;
  parentId: ID | null;
  hasChildren: boolean;
  /** Children one level down, whether or not they are currently shown. */
  childCount: number;
  /** 1-based position among its siblings, and how many siblings there are. */
  position: number;
  siblings: number;
}

/**
 * One level of a tree, described by what sits under a parent. Asked with
 * `null`, a level returns its roots: the nodes the level above has nothing to
 * hang from — every epic, but also a story that has no epic.
 *
 * The traversal below knows nothing about epics or stories. A level above
 * `Epic` (VEC-24) is one more entry at the front of the list, not a change to it.
 */
export interface TreeLevel {
  kind: string;
  childrenOf(parentId: ID | null): readonly ID[];
}

/**
 * Projects a tree onto the flat, ordered list of rows currently visible under
 * `expanded`. A collapsed row contributes itself and nothing below it, so the
 * consumer renders the list as it comes and never filters.
 *
 * Roots come level by level: every root of the first level, then the roots of
 * the next (a story with no epic after all the epics), and so on.
 */
export function flattenTree(
  levels: readonly TreeLevel[],
  expanded: ReadonlySet<ID>,
): TreeRow[] {
  const rows: TreeRow[] = [];

  const visit = (
    ids: readonly ID[],
    levelIndex: number,
    depth: number,
    parentId: ID | null,
    siblings: number,
    firstPosition: number,
  ) => {
    const next = levels[levelIndex + 1];
    ids.forEach((id, index) => {
      const children = next ? next.childrenOf(id) : [];
      rows.push({
        id,
        kind: levels[levelIndex].kind,
        depth,
        parentId,
        hasChildren: children.length > 0,
        childCount: children.length,
        position: firstPosition + index,
        siblings,
      });
      if (children.length > 0 && expanded.has(id)) {
        visit(children, levelIndex + 1, depth + 1, id, children.length, 1);
      }
    });
  };

  const roots = levels.map((level) => level.childrenOf(null));
  const rootCount = roots.reduce((sum, ids) => sum + ids.length, 0);
  let position = 1;
  roots.forEach((ids, levelIndex) => {
    visit(ids, levelIndex, 0, null, rootCount, position);
    position += ids.length;
  });
  return rows;
}

/** Epics that parent at least one issue anywhere, cached per `issuesById` identity. */
const epicsInUseCache = new WeakMap<Record<ID, Issue>, Set<ID>>();

function epicsInUse(issuesById: Record<ID, Issue>): Set<ID> {
  let inUse = epicsInUseCache.get(issuesById);
  if (!inUse) {
    inUse = new Set();
    for (const issue of Object.values(issuesById)) if (issue.epicId) inUse.add(issue.epicId);
    epicsInUseCache.set(issuesById, inUse);
  }
  return inUse;
}

type TreeSource = Pick<
  WorkspaceState,
  "issuesById" | "epicsById" | "boardsById" | "boardIssueIds"
>;

/**
 * The two levels a board's backlog has today: epic → issue.
 *
 * Issues are ordered as the board reads left to right — by column, then by
 * their order within it. Epics are ordered by key.
 *
 * Which epics a board shows: an epic with at least one issue on this board, and
 * an epic with no issues on any board (shown with a zero count, since that is
 * backlog still to be planned). An epic whose issues all sit on other boards is
 * left off: it has children, just not here, and a zero beside it would say
 * otherwise.
 */
export function boardTreeLevels(state: TreeSource, boardId: ID): TreeLevel[] {
  const board = state.boardsById[boardId];
  const columnOrder = new Map(board?.columns.map((column) => [column.id, column.order]) ?? []);
  const rank = (issue: Issue) => columnOrder.get(issue.columnId) ?? Number.MAX_SAFE_INTEGER;

  const issues = boardIssues(state.issuesById, state.boardIssueIds, boardId).sort(
    (a, b) => rank(a) - rank(b) || a.order - b.order,
  );

  const byEpic = new Map<ID | null, ID[]>();
  for (const issue of issues) {
    const parent = issue.epicId && state.epicsById[issue.epicId] ? issue.epicId : null;
    const bucket = byEpic.get(parent);
    if (bucket) bucket.push(issue.id);
    else byEpic.set(parent, [issue.id]);
  }

  const inUse = epicsInUse(state.issuesById);
  const epicIds = Object.values(state.epicsById)
    .filter((epic) => byEpic.has(epic.id) || !inUse.has(epic.id))
    .sort((a, b) => a.key.localeCompare(b.key, undefined, { numeric: true }))
    .map((epic) => epic.id);

  return [
    { kind: "epic", childrenOf: (parentId) => (parentId === null ? epicIds : []) },
    { kind: "issue", childrenOf: (parentId) => byEpic.get(parentId) ?? [] },
  ];
}

/** The visible rows of a board's backlog tree under the given expansion set. */
export function selectFlatTree(
  state: TreeSource,
  boardId: ID,
  expandedIds: ReadonlySet<ID>,
): TreeRow[] {
  return flattenTree(boardTreeLevels(state, boardId), expandedIds);
}
