import { ApiError, type Problem } from "./errors";

/**
 * The transport shapes, and the narrowing that turns an untrusted `unknown`
 * body into them.
 *
 * These mirror the `/api/v1` read views of `WorkspacesResource` (VEC-94) field
 * for field, including the parts the client's own normalized shape does not
 * have (`rank`, `fields`, `version`). Following ADR-VEC-05 §3 they carry keys,
 * never ids: a workspace, a board, a column (within its board) and an item are
 * each named by their key. They are deliberately *not* the store's types: keeping the wire
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

/** `{key, version}` of the template a workspace is pinned to. */
export interface WireTemplateRef {
  key: string;
  version: number;
}

/** `GET /workspaces/{key}`: `WorkspacesResource.WorkspaceView`. */
export interface WireWorkspace {
  key: string;
  name: string;
  template: WireTemplateRef;
}

/** A column of `WorkspacesResource.BoardView`. Its key is unique within its board only. */
export interface WireBoardColumn {
  key: string;
  name: string;
  /** Dense display index. Not an ordering key for the items inside the column. */
  ordinal: number;
  /** False for a column the workflow keeps off the board (a backlog, an archive). */
  onBoard: boolean;
}

/** `GET /workspaces/{key}/boards`, one entry: `WorkspacesResource.BoardView`. */
export interface WireBoard {
  key: string;
  name: string;
  columns: WireBoardColumn[];
}

/** One item of a page: `WorkspacesResource.ItemView`. Board and column are keys. */
export interface WireItem {
  /** Unique within the workspace. */
  key: string;
  title: string;
  board: string;
  column: string;
  /** Sparse lexicographic rank — the ordering key within a column. */
  rank: string;
  /** The open document: everything the relational columns do not model. */
  fields: Record<string, WireJsonValue>;
  /** The revision a later write takes back as `If-Match`. */
  version: number;
}

/** `GET /workspaces/{key}/items`: newest first, `next` null on the last page. */
export interface WireItemPage {
  items: WireItem[];
  next: string | null;
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

function asBoolean(record: Record<string, unknown>, key: string, path: string): boolean {
  const value = record[key];
  if (typeof value !== "boolean") fail(`${path}.${key}`, "a boolean", value);
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
  const template = asRecord(record.template, `${path}.template`);
  return {
    key: asString(record, "key", path),
    name: asString(record, "name", path),
    template: {
      key: asString(template, "key", `${path}.template`),
      version: asInteger(template, "version", `${path}.template`),
    },
  };
}

export function decodeBoardColumn(value: unknown, path: string): WireBoardColumn {
  const record = asRecord(value, path);
  return {
    key: asString(record, "key", path),
    name: asString(record, "name", path),
    ordinal: asInteger(record, "ordinal", path),
    onBoard: asBoolean(record, "onBoard", path),
  };
}

export function decodeBoard(value: unknown, path = "board"): WireBoard {
  const record = asRecord(value, path);
  return {
    key: asString(record, "key", path),
    name: asString(record, "name", path),
    columns: asArray(record.columns, `${path}.columns`).map((column, i) =>
      decodeBoardColumn(column, `${path}.columns[${i}]`),
    ),
  };
}

export function decodeItem(value: unknown, path = "item"): WireItem {
  const record = asRecord(value, path);
  return {
    key: asString(record, "key", path),
    title: asString(record, "title", path),
    board: asString(record, "board", path),
    column: asString(record, "column", path),
    rank: asString(record, "rank", path),
    fields: asFields(record, "fields", path),
    version: asInteger(record, "version", path),
  };
}

export function decodeBoards(value: unknown, path = "boards"): WireBoard[] {
  return asArray(value, path).map((board, i) => decodeBoard(board, `${path}[${i}]`));
}

/** `next` must be present: an absent one is a server that forgot it, not a last page. */
export function decodeItemPage(value: unknown, path = "items"): WireItemPage {
  const record = asRecord(value, path);
  if (!("next" in record)) fail(`${path}.next`, "a string or null", undefined);
  return {
    items: asArray(record.items, `${path}.items`).map((item, i) =>
      decodeItem(item, `${path}.items[${i}]`),
    ),
    next: asNullableString(record, "next", path),
  };
}

/**
 * An RFC 9457 body (ADR-VEC-05), or null when the body is not one. Lenient on
 * purpose: it reads an error that is already being reported, and a malformed
 * problem must leave the plain status error standing rather than replace it.
 */
export function readProblem(value: unknown): Problem | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const record = value as Record<string, unknown>;
  const { type, title, status, detail } = record;
  if (typeof title !== "string" || typeof status !== "number" || !Number.isInteger(status)) {
    return null;
  }
  return {
    type: typeof type === "string" ? type : "about:blank",
    title,
    status,
    detail: typeof detail === "string" ? detail : null,
  };
}
