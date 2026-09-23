import { ApiError } from "./errors";

/**
 * The transport shapes, and the narrowing that turns an untrusted `unknown`
 * body into them.
 *
 * These mirror the server aggregates in `vectis-domain` — `Workspace`, `Board`,
 * `BoardColumn`, `Item` — field for field, including the parts of the model that
 * the client's own normalized shape does not have (`rank`, `fields`,
 * `sprintId`). They are deliberately *not* the store's types: keeping the wire
 * shape separate is what lets `src/store/types.ts` stay a client concern, and
 * what confines a future contract change to this file plus `adapter.ts`.
 *
 * Nothing here trusts the network. Every decoder narrows from `unknown` and
 * throws `ApiError("format", …)` naming the offending path, so a server that
 * drifts from the contract produces a diagnosable error rather than `undefined`
 * surfacing three layers away.
 */

/** A value that survives `JSON.parse` — the type of everything in `Item.fields`. */
export type WireJsonValue =
  | string
  | number
  | boolean
  | null
  | WireJsonValue[]
  | { [key: string]: WireJsonValue };

/** Mirrors `io.vectis.domain.Workspace`. */
export interface WireWorkspace {
  id: string;
  key: string;
  name: string;
}

/** Mirrors `io.vectis.domain.BoardColumn`. */
export interface WireBoardColumn {
  id: string;
  name: string;
  /** Dense display index. Not an ordering key for the items inside the column. */
  position: number;
}

/** Mirrors `io.vectis.domain.Board`. */
export interface WireBoard {
  id: string;
  workspaceId: string;
  name: string;
  columns: WireBoardColumn[];
}

/** Mirrors `io.vectis.domain.Item`. */
export interface WireItem {
  id: string;
  workspaceId: string;
  boardId: string;
  columnId: string;
  key: string;
  title: string;
  /** Sparse lexicographic rank — the ordering key within a column. */
  rank: string;
  /** The open document: everything the relational columns do not model. */
  fields: Record<string, WireJsonValue>;
  sprintId: string | null;
}

function fail(path: string, expected: string, actual: unknown): never {
  throw new ApiError(
    "format",
    `${path}: expected ${expected}, received ${describe(actual)}`,
  );
}

function describe(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "an array";
  return typeof value;
}

function asRecord(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    fail(path, "an object", value);
  }
  return value as Record<string, unknown>;
}

function asArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) fail(path, "an array", value);
  return value;
}

function asString(record: Record<string, unknown>, key: string, path: string): string {
  const value = record[key];
  if (typeof value !== "string") fail(`${path}.${key}`, "a string", value);
  return value;
}

function asNullableString(
  record: Record<string, unknown>,
  key: string,
  path: string,
): string | null {
  const value = record[key];
  if (value === null || value === undefined) return null;
  if (typeof value !== "string") fail(`${path}.${key}`, "a string or null", value);
  return value;
}

function asInteger(record: Record<string, unknown>, key: string, path: string): number {
  const value = record[key];
  if (typeof value !== "number" || !Number.isInteger(value)) {
    fail(`${path}.${key}`, "an integer", value);
  }
  return value;
}

/**
 * `fields` is open by contract, so it is checked for JSON-ness rather than for a
 * schema. An absent document is read as an empty one: the server models it as
 * `Map.of()`, and a serializer that omits an empty map is not a contract breach.
 */
function asFields(
  record: Record<string, unknown>,
  key: string,
  path: string,
): Record<string, WireJsonValue> {
  const value = record[key];
  if (value === undefined || value === null) return {};
  const fields = asRecord(value, `${path}.${key}`);
  for (const [name, entry] of Object.entries(fields)) {
    assertJsonValue(entry, `${path}.${key}.${name}`);
  }
  return fields as Record<string, WireJsonValue>;
}

function assertJsonValue(value: unknown, path: string): void {
  if (value === null) return;
  switch (typeof value) {
    case "string":
    case "boolean":
      return;
    case "number":
      if (!Number.isFinite(value)) fail(path, "a finite number", value);
      return;
    case "object":
      if (Array.isArray(value)) {
        value.forEach((entry, i) => assertJsonValue(entry, `${path}[${i}]`));
        return;
      }
      for (const [name, entry] of Object.entries(value as Record<string, unknown>)) {
        assertJsonValue(entry, `${path}.${name}`);
      }
      return;
    default:
      fail(path, "a JSON value", value);
  }
}

export function decodeWorkspace(value: unknown, path = "workspace"): WireWorkspace {
  const record = asRecord(value, path);
  return {
    id: asString(record, "id", path),
    key: asString(record, "key", path),
    name: asString(record, "name", path),
  };
}

export function decodeBoardColumn(value: unknown, path: string): WireBoardColumn {
  const record = asRecord(value, path);
  return {
    id: asString(record, "id", path),
    name: asString(record, "name", path),
    position: asInteger(record, "position", path),
  };
}

export function decodeBoard(value: unknown, path = "board"): WireBoard {
  const record = asRecord(value, path);
  return {
    id: asString(record, "id", path),
    workspaceId: asString(record, "workspaceId", path),
    name: asString(record, "name", path),
    columns: asArray(record.columns, `${path}.columns`).map((column, i) =>
      decodeBoardColumn(column, `${path}.columns[${i}]`),
    ),
  };
}

export function decodeItem(value: unknown, path = "item"): WireItem {
  const record = asRecord(value, path);
  return {
    id: asString(record, "id", path),
    workspaceId: asString(record, "workspaceId", path),
    boardId: asString(record, "boardId", path),
    columnId: asString(record, "columnId", path),
    key: asString(record, "key", path),
    title: asString(record, "title", path),
    rank: asString(record, "rank", path),
    fields: asFields(record, "fields", path),
    sprintId: asNullableString(record, "sprintId", path),
  };
}

export function decodeBoards(value: unknown, path = "boards"): WireBoard[] {
  return asArray(value, path).map((board, i) => decodeBoard(board, `${path}[${i}]`));
}

export function decodeItems(value: unknown, path = "items"): WireItem[] {
  return asArray(value, path).map((item, i) => decodeItem(item, `${path}[${i}]`));
}
