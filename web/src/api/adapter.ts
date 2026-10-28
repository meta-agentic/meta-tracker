import type {
  Board,
  Column,
  Epic,
  ID,
  Issue,
  WorkspaceSnapshot,
} from "../store/types";
import type { WireBoard, WireItem, WireJsonValue, WireWorkspace } from "./wire";

/**
 * Wire shape to `WorkspaceSnapshot`.
 *
 * The server model and the client model are not the same model, and this is the
 * only place that knows it. The store keeps a denormalized, render-ready shape;
 * the domain keeps an indexable relational core plus an open `fields` document.
 * The API names everything by key (ADR-VEC-05 §3), so the store's ids are keys:
 * a board's id is its key, an item's id is its key, and a column's id is its
 * key, which is unique within its board, the only scope the store looks a
 * column up in. Three further differences have to be reconciled here:
 *
 *  1. **Ordering.** `Item.rank` is a sparse lexicographic string so a drag
 *     updates one row. `Issue.order` is a dense integer because
 *     `store/selectors.ts` sorts numerically. The rank order is preserved by
 *     sorting on it and projecting to indices, per column.
 *  2. **Epics.** `vectis-domain` has no `Epic` record — there is exactly one
 *     work-unit type, `Item`, and hierarchy therefore lives in `fields`. An item
 *     carrying `fields.type === "epic"` is lifted out of the issue list into
 *     `Epic`; every other item points at one by key through `fields.parentId`.
 *  3. **The long tail.** Schedule dates, story points, type, labels, priority,
 *     description and item links are not relational columns on `Item`; they
 *     are read out of `fields` and are null (or empty) when absent or
 *     malformed, never guessed.
 *
 * Every assumption above is a field name, and every field name is a constant
 * below, so a disagreement with an importer is a one-line edit here rather
 * than a hunt through the client.
 */

/** `Item.fields` keys this adapter reads. Nothing else in the client knows them. */
export const FIELD = {
  type: "type",
  parentId: "parentId",
  color: "color",
  startDate: "startDate",
  dueDate: "dueDate",
  storyPoints: "storyPoints",
  labels: "labels",
  priority: "priority",
  description: "description",
  dependencies: "dependencies",
  relates: "relates",
} as const;

/** The `fields.type` value that marks an item as an epic rather than an issue. */
export const EPIC_TYPE = "epic";

/**
 * Fallback epic colours. Deliberately duplicated rather than imported from the
 * synthetic generator: nothing on the application entry path may reach that
 * module, and a shared palette constant would be exactly that reach.
 */
const EPIC_COLORS = [
  "#4f46e5",
  "#0891b2",
  "#059669",
  "#d97706",
  "#dc2626",
  "#7c3aed",
];

/** FNV-1a, so an epic without a stored colour keeps the same one across loads. */
function stableColor(key: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < key.length; i += 1) {
    hash ^= key.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return EPIC_COLORS[(hash >>> 0) % EPIC_COLORS.length];
}

function readString(
  fields: Record<string, WireJsonValue>,
  key: string,
): string | null {
  const value = fields[key];
  return typeof value === "string" && value.length > 0 ? value : null;
}

function readNumber(
  fields: Record<string, WireJsonValue>,
  key: string,
): number | null {
  const value = fields[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * A list of distinct non-empty strings, in first-seen order. Any other entry is
 * dropped, not coerced; a repeat is dropped because every consumer treats the
 * list as a set (and keys its rendering on the value).
 */
function readStrings(
  fields: Record<string, WireJsonValue>,
  key: string,
): string[] {
  const value = fields[key];
  if (!Array.isArray(value)) return [];
  const strings = value.filter(
    (entry): entry is string => typeof entry === "string" && entry.length > 0,
  );
  return [...new Set(strings)];
}

function isEpic(item: WireItem): boolean {
  return readString(item.fields, FIELD.type) === EPIC_TYPE;
}

/**
 * The columns the board shows, in ordinal order. A column kept off the board
 * keeps its items: they are issues like any other and appear in the backlog,
 * they just have no column on the board to render in.
 */
function toColumns(board: WireBoard): Column[] {
  return board.columns
    .filter((column) => column.onBoard)
    .sort((a, b) => a.ordinal - b.ordinal)
    .map((column) => ({
      id: column.key,
      name: column.name,
      order: column.ordinal,
    }));
}

function toEpic(item: WireItem): Epic {
  return {
    id: item.key,
    key: item.key,
    title: item.title,
    color: readString(item.fields, FIELD.color) ?? stableColor(item.key),
  };
}

/**
 * Dense `order` per column, derived from `rank`.
 *
 * The key is the tiebreak, not the position in the response: two items sharing a
 * rank must still land in a stable order, and the response is newest first,
 * which says nothing about where an item sits in its column.
 */
function assignOrders(items: WireItem[]): Map<ID, number> {
  const byColumn = new Map<string, WireItem[]>();
  for (const item of items) {
    const bucket = `${item.board}\u0000${item.column}`;
    const existing = byColumn.get(bucket);
    if (existing) existing.push(item);
    else byColumn.set(bucket, [item]);
  }

  const orders = new Map<ID, number>();
  for (const bucket of byColumn.values()) {
    bucket
      .sort((a, b) => (a.rank === b.rank ? compare(a.key, b.key) : compare(a.rank, b.rank)))
      .forEach((item, index) => orders.set(item.key, index));
  }
  return orders;
}

function compare(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export interface WireWorkspacePayload {
  workspace: WireWorkspace;
  boards: WireBoard[];
  items: WireItem[];
}

export function toWorkspaceSnapshot(payload: WireWorkspacePayload): WorkspaceSnapshot {
  const { workspace, boards: wireBoards, items } = payload;

  const boards: Board[] = wireBoards.map((board) => ({
    id: board.key,
    key: board.key,
    name: board.name,
    columns: toColumns(board),
  }));

  const boardIds = new Set(boards.map((board) => board.id));
  // An item on a board this response did not carry has nowhere to render, and
  // admitting it would put an unreachable id into `boardIssueIds`.
  const placeable = items.filter((item) => boardIds.has(item.board));

  const epicItems = placeable.filter(isEpic);
  const epics: Epic[] = epicItems.map(toEpic);
  const epicIds = new Set(epics.map((epic) => epic.id));

  const issueItems = placeable.filter((item) => !isEpic(item));
  const orders = assignOrders(issueItems);

  const issues: Issue[] = issueItems.map((item) => {
    const parentId = readString(item.fields, FIELD.parentId);
    return {
      id: item.key,
      key: item.key,
      boardId: item.board,
      // A parent that is not an epic in this payload is dropped rather than
      // carried: the board colours by epic, and a dangling id would colour
      // nothing while reading as present.
      epicId: parentId !== null && epicIds.has(parentId) ? parentId : null,
      columnId: item.column,
      title: item.title,
      order: orders.get(item.key) ?? 0,
      startDate: readString(item.fields, FIELD.startDate),
      dueDate: readString(item.fields, FIELD.dueDate),
      storyPoints: readNumber(item.fields, FIELD.storyPoints),
      type: readString(item.fields, FIELD.type),
      labels: readStrings(item.fields, FIELD.labels),
      priority: readString(item.fields, FIELD.priority),
      description: readString(item.fields, FIELD.description),
      dependsOn: readStrings(item.fields, FIELD.dependencies),
      relates: readStrings(item.fields, FIELD.relates),
    };
  });

  return {
    workspace: { key: workspace.key, name: workspace.name },
    boards,
    epics,
    issues,
  };
}
