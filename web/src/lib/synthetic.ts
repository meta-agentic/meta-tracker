import type {
  Board,
  Column,
  Epic,
  Issue,
  WorkspaceSnapshot,
} from "../store/types";

/** Deterministic PRNG (mulberry32) so generated workspaces are reproducible
 * across runs — important for stable profiling comparisons. */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const COLUMNS: Column[] = [
  { id: "col-backlog", name: "Backlog", order: 0 },
  { id: "col-todo", name: "To Do", order: 1 },
  { id: "col-doing", name: "In Progress", order: 2 },
  { id: "col-review", name: "In Review", order: 3 },
  { id: "col-done", name: "Done", order: 4 },
];

const EPIC_COLORS = [
  "#4f46e5",
  "#0891b2",
  "#059669",
  "#d97706",
  "#dc2626",
  "#7c3aed",
];

const DAY_MS = 86_400_000;

export interface GenerateOptions {
  boards?: number;
  epics?: number;
  issues: number;
  /** Span the schedule across this many years from `startYear`. */
  years?: number;
  startYear?: number;
  seed?: number;
}

export function generateWorkspace(opts: GenerateOptions): WorkspaceSnapshot {
  const {
    boards: boardCount = 1,
    epics: epicCount = 12,
    issues: issueCount,
    years = 3,
    startYear = 2024,
    seed = 1,
  } = opts;
  const rand = mulberry32(seed);

  const boards: Board[] = Array.from({ length: boardCount }, (_, b) => ({
    id: `board-${b}`,
    key: `B${b + 1}`,
    name: `Team Board ${b + 1}`,
    columns: COLUMNS,
  }));

  const epics: Epic[] = Array.from({ length: epicCount }, (_, e) => ({
    id: `epic-${e}`,
    key: `EP-${e + 1}`,
    title: `Epic ${e + 1}`,
    color: EPIC_COLORS[e % EPIC_COLORS.length],
  }));

  const spanDays = years * 365;
  const start = Date.UTC(startYear, 0, 1);

  const issues: Issue[] = Array.from({ length: issueCount }, (_, i) => {
    const boardId = `board-${Math.floor(rand() * boardCount)}`;
    const column = COLUMNS[Math.floor(rand() * COLUMNS.length)];
    const startOffset = Math.floor(rand() * spanDays);
    const duration = 1 + Math.floor(rand() * 30);
    const startMs = start + startOffset * DAY_MS;
    const dueMs = startMs + duration * DAY_MS;
    return {
      id: `issue-${i}`,
      key: `VEC-${i + 1}`,
      boardId,
      epicId: `epic-${Math.floor(rand() * epicCount)}`,
      columnId: column.id,
      title: `Task ${i + 1}`,
      order: i,
      startDate: new Date(startMs).toISOString().slice(0, 10),
      dueDate: new Date(dueMs).toISOString().slice(0, 10),
      storyPoints: [1, 2, 3, 5, 8][Math.floor(rand() * 5)],
    };
  });

  return { boards, epics, issues };
}
