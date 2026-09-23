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
 * Three differences have to be reconciled here:
 *
 *  1. **Ordering.** `Item.rank` is a sparse lexicographic string so a drag
 *     updates one row. `Issue.order` is a dense integer because
 *     `store/selectors.ts` sorts numerically. The rank order is preserved by
 *     sorting on it and projecting to indices, per column.
 *  2. **Epics.** `vectis-domain` has no `Epic` record — there is exactly one
 *     work-unit type, `Item`, and hierarchy therefore lives in `fields`. An item
 *     carrying `fields.type === "epic"` is lifted out of the issue list into
 *     `Epic`; every other item points at one through `fields.parentId`.
 *  3. **The long tail.** Schedule dates and story points are not relational
 *     columns on `Item`; they are read out of `fields` and are null when absent
 *     or malformed, never guessed.
 *
 * Every assumption above is a field name, and every field name is a constant
 * below. When VEC-10 publishes the endpoint contract, a disagreement is a
 * one-line edit here rather than a hunt through the client.
 */

/** `Item.fields` keys this adapter reads. Nothing else in the client knows them. */
export const FIELD = {
  type: "type",
  parentId: "parentId",
  color: "color",
  startDate: "startDate",
  dueDate: "dueDate",
  storyPoints: "storyPoints",
} as const;

/** The `fields.type` value that marks an item as an epic rather than an issue. */
export const EPIC_TYPE = "epic";

/**
 * Fallback epic colours. Held here rather than imported from `lib/synthetic`:
 * VEC-42 requires that nothing on the application entry path reach the
 * generator, and a shared palette constant would be exactly that reach.
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

function isEpic(item: WireItem): boolean {
  return readString(item.fields, FIELD.type) === EPIC_TYPE;
}

function toColumns(board: WireBoard): Column[] {
  return board.columns
    .slice()
    .sort((a, b) => a.position - b.position)
    .map((column) => ({
      id: column.id,
      name: column.name,
      order: column.position,
    }));
}

function toEpic(item: WireItem): Epic {
  return {
    id: item.id,
    key: item.key,
    title: item.title,
    color: readString(item.fields, FIELD.color) ?? stableColor(item.key),
  };
}

/**
 * Dense `order` per column, derived from `rank`.
 *
 * The key is the tiebreak, not the position in the response: two items sharing a
 * rank must still land in a stable order, and array position is whatever the
 * server's query happened to return.
 */
function assignOrders(items: WireItem[]): Map<ID, number> {
  const byColumn = new Map<string, WireItem[]>();
  for (const item of items) {
    const bucket = `${item.boardId}\u0000${item.columnId}`;
    const existing = byColumn.get(bucket);
    if (existing) existing.push(item);
    else byColumn.set(bucket, [item]);
  }

  const orders = new Map<ID, number>();
  for (const bucket of byColumn.values()) {
    bucket
      .sort((a, b) => (a.rank === b.rank ? compare(a.key, b.key) : compare(a.rank, b.rank)))
      .forEach((item, index) => orders.set(item.id, index));
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
    id: board.id,
    // The domain holds no per-board key: item keys are minted from the
    // workspace prefix, so every board in a workspace shares it.
    key: workspace.key,
    name: board.name,
    columns: toColumns(board),
  }));

  const boardIds = new Set(boards.map((board) => board.id));
  // An item on a board this response did not carry has nowhere to render, and
  // admitting it would put an unreachable id into `boardIssueIds`.
  const placeable = items.filter((item) => boardIds.has(item.boardId));

  const epicItems = placeable.filter(isEpic);
  const epics: Epic[] = epicItems.map(toEpic);
  const epicIds = new Set(epics.map((epic) => epic.id));

  const issueItems = placeable.filter((item) => !isEpic(item));
  const orders = assignOrders(issueItems);

  const issues: Issue[] = issueItems.map((item) => {
    const parentId = readString(item.fields, FIELD.parentId);
    return {
      id: item.id,
      key: item.key,
      boardId: item.boardId,
      // A parent that is not an epic in this payload is dropped rather than
      // carried: the board colours by epic, and a dangling id would colour
      // nothing while reading as present.
      epicId: parentId !== null && epicIds.has(parentId) ? parentId : null,
      columnId: item.columnId,
      title: item.title,
      order: orders.get(item.id) ?? 0,
      startDate: readString(item.fields, FIELD.startDate),
      dueDate: readString(item.fields, FIELD.dueDate),
      storyPoints: readNumber(item.fields, FIELD.storyPoints),
    };
  });

  return { boards, epics, issues };
}
