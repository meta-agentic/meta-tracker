import type { Board, Epic, ID, Issue } from "../store/types";

/** The most ancestors followed. A vault file is untrusted; a chain is never this deep. */
export const MAX_CHAIN = 16;

/**
 * An epic read as an issue, so the one detail panel opens either. The epic's
 * parent is its `epicId`; fields an entry cached before epics carried them
 * lacks read as empty.
 */
export function epicAsIssue(epic: Epic): Issue {
  return {
    id: epic.id,
    key: epic.key,
    boardId: epic.boardId ?? "",
    epicId: epic.epicId ?? null,
    columnId: epic.columnId ?? "",
    title: epic.title,
    order: 0,
    startDate: epic.startDate ?? null,
    dueDate: epic.dueDate ?? null,
    storyPoints: epic.storyPoints ?? null,
    type: epic.type ?? "epic",
    labels: epic.labels ?? [],
    priority: epic.priority ?? null,
    description: epic.description ?? null,
    dependsOn: epic.dependsOn ?? [],
    relates: epic.relates ?? [],
    sprint: epic.sprint ?? null,
    extension: epic.extension ?? null,
    intension: epic.intension ?? null,
  };
}

/** An epic or an issue, read the one way the detail panel needs. */
export interface ItemNode {
  id: ID;
  key: string;
  title: string;
  isEpic: boolean;
  /** Status: the name of the item's column on its board, null when unknown. */
  status: string | null;
  type: string | null;
  priority: string | null;
  points: number | null;
  sprint: string | null;
  extension: number | null;
  intension: number | null;
  /** The epic above it, by id. */
  parentId: ID | null;
  dependsOn: string[];
  relates: string[];
}

export interface ItemIndex {
  issuesById: Record<ID, Issue>;
  epicsById: Record<ID, Epic>;
  boardsById: Record<ID, Board>;
}

function statusOf(index: ItemIndex, boardId?: ID, columnId?: ID): string | null {
  if (!boardId || !columnId) return null;
  return index.boardsById[boardId]?.columns.find((c) => c.id === columnId)?.name ?? null;
}

/** The item with this id, whichever kind it is; null when neither store holds it. */
export function nodeOf(index: ItemIndex, id: ID): ItemNode | null {
  const issue = Object.hasOwn(index.issuesById, id) ? index.issuesById[id] : undefined;
  if (issue) {
    return {
      id: issue.id,
      key: issue.key,
      title: issue.title,
      isEpic: false,
      status: statusOf(index, issue.boardId, issue.columnId),
      type: issue.type,
      priority: issue.priority,
      points: issue.storyPoints,
      sprint: issue.sprint ?? null,
      extension: issue.extension ?? null,
      intension: issue.intension ?? null,
      parentId: issue.epicId,
      dependsOn: issue.dependsOn,
      relates: issue.relates,
    };
  }
  const epic = Object.hasOwn(index.epicsById, id) ? index.epicsById[id] : undefined;
  if (!epic) return null;
  return {
    id: epic.id,
    key: epic.key,
    title: epic.title,
    isEpic: true,
    status: statusOf(index, epic.boardId, epic.columnId),
    type: epic.type ?? "epic",
    priority: epic.priority ?? null,
    points: epic.storyPoints ?? null,
    sprint: epic.sprint ?? null,
    extension: epic.extension ?? null,
    intension: epic.intension ?? null,
    parentId: epic.epicId ?? null,
    dependsOn: epic.dependsOn ?? [],
    relates: epic.relates ?? [],
  };
}

/**
 * The ancestors of an item, outermost first. Stops at an id already seen (a
 * cycle, or an epic under itself) and after {@link MAX_CHAIN} steps.
 */
export function ancestorChain(index: ItemIndex, id: ID): ItemNode[] {
  const seen = new Set<ID>([id]);
  const chain: ItemNode[] = [];
  let next = nodeOf(index, id)?.parentId ?? null;
  while (next !== null && !seen.has(next) && chain.length < MAX_CHAIN) {
    seen.add(next);
    const parent = nodeOf(index, next);
    if (!parent) break;
    chain.unshift(parent);
    next = parent.parentId;
  }
  return chain;
}

export type LinkGroupKey =
  | "dependsOn"
  | "relates"
  | "dependedOnBy"
  | "relatedFrom"
  | "children";

/** One row of a link group: the item when it is loaded, otherwise only its key. */
export interface LinkRow {
  key: string;
  node: ItemNode | null;
}

export interface LinkGroup {
  group: LinkGroupKey;
  rows: LinkRow[];
}

/**
 * Every link group of an item, empty ones left out. The forward groups come
 * from the item's own fields; the reverse ones are computed from everything
 * loaded, so they hold only items the client has. A link to the item itself is
 * dropped: a row that opens the panel already open says nothing.
 */
export function linkGroups(index: ItemIndex, id: ID): LinkGroup[] {
  const self = nodeOf(index, id);
  if (!self) return [];
  const byKey = new Map<string, ItemNode>();
  const all: ItemNode[] = [];
  for (const nodeId of [...Object.keys(index.epicsById), ...Object.keys(index.issuesById)]) {
    const node = nodeOf(index, nodeId);
    if (node) {
      byKey.set(node.key, node);
      all.push(node);
    }
  }
  const forward = (keys: string[]): LinkRow[] =>
    [...new Set(keys)]
      .filter((key) => key !== self.key)
      .map((key) => ({ key, node: byKey.get(key) ?? null }));
  const reverse = (pick: (n: ItemNode) => string[]): LinkRow[] =>
    all
      .filter((n) => n.id !== self.id && pick(n).includes(self.key))
      .map((n) => ({ key: n.key, node: n }));

  const groups: LinkGroup[] = [
    { group: "dependsOn", rows: forward(self.dependsOn) },
    { group: "relates", rows: forward(self.relates) },
    { group: "dependedOnBy", rows: reverse((n) => n.dependsOn) },
    { group: "relatedFrom", rows: reverse((n) => n.relates) },
  ];
  if (self.isEpic) {
    groups.push({
      group: "children",
      rows: all.filter((n) => n.id !== self.id && n.parentId === self.id).map((n) => ({ key: n.key, node: n })),
    });
  }
  return groups.filter((g) => g.rows.length > 0);
}
