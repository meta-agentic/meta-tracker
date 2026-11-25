// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike). An in-memory stand-in for the item
// endpoints, answering ADR-VEC-05 problem documents. It runs the same rules as
// the client (forms.ts `check`) plus one the client cannot run: the parent
// must be an existing item of an allowed type, which only the server knows.

import { check, patch, withDefaults, type Effective, type Stored, type Violation } from "./forms";

export interface Problem {
  type: "about:blank";
  title: string;
  status: number;
  detail: string;
  violations?: Violation[];
}

export type Answer = { status: 200 | 201; item: StoredItem } | { status: 412 | 422; problem: Problem };

export interface StoredItem extends Stored {
  key: string;
  version: number;
}

export class MockServer {
  private readonly items = new Map<string, StoredItem>();
  private seq = 0;

  constructor(readonly cfg: Effective, readonly prefix: string) {}

  seed(state: string, title: string, fields: Record<string, unknown>): StoredItem {
    const item = { key: `${this.prefix}-${++this.seq}`, state, title, fields, version: 1 };
    this.items.set(item.key, item);
    return item;
  }

  get(key: string): StoredItem | undefined {
    return this.items.get(key);
  }

  all(): StoredItem[] {
    return [...this.items.values()];
  }

  create(body: { column: string; title: string; fields: Record<string, unknown> }): Answer {
    const violations = [...check(this.cfg, { op: "create", state: body.column, title: body.title, fields: body.fields }), ...this.parent(body.fields)];
    if (violations.length) return rejected(violations);
    const type = (body.fields.type as string) ?? this.cfg.settings.defaultItemType;
    const fields = withDefaults(this.cfg, type, { ...body.fields, type });
    return { status: 201, item: this.seed(body.column, body.title, fields) };
  }

  edit(key: string, version: number, body: { title?: string; fields?: Record<string, unknown> }): Answer {
    const item = this.items.get(key)!;
    if (item.version !== version) return stale(key);
    const violations = [...check(this.cfg, { op: "edit", item, ...body }), ...this.parent(body.fields ?? {})];
    if (violations.length) return rejected(violations);
    const saved = { ...item, title: body.title ?? item.title, fields: patch(item.fields, body.fields), version: item.version + 1 };
    this.items.set(key, saved);
    return { status: 200, item: saved };
  }

  /** The proposed move: column plus an optional field patch, one version, one event. */
  move(key: string, version: number, body: { column: string; fields?: Record<string, unknown> }): Answer {
    const item = this.items.get(key)!;
    if (item.version !== version) return stale(key);
    const violations = check(this.cfg, { op: "move", item, to: body.column, fields: body.fields });
    if (violations.length) return rejected(violations);
    const saved = { ...item, state: body.column, fields: patch(item.fields, body.fields), version: item.version + 1 };
    this.items.set(key, saved);
    return { status: 200, item: saved };
  }

  /** Server-only: the parent exists and its type may hold this one. */
  private parent(fields: Record<string, unknown>): Violation[] {
    const key = fields.parentId;
    if (key === undefined || key === null || key === "") return [];
    const parent = this.items.get(String(key));
    const type = (fields.type as string) ?? this.cfg.settings.defaultItemType;
    const allowed = this.cfg.itemTypes[type]?.parents ?? [];
    if (!parent || !allowed.includes(parent.fields.type as string)) {
      return [{ field: "fields.parentId", level: "workspace", code: "parent", message: `${String(key)} is not an item a ${type} may sit under (${allowed.join(", ") || "none"})` }];
    }
    return [];
  }
}

function rejected(violations: Violation[]): Answer {
  return { status: 422, problem: { type: "about:blank", title: "Unprocessable Content", status: 422, detail: "the item breaks the workspace's rules", violations } };
}

function stale(key: string): Answer {
  return { status: 412, problem: { type: "about:blank", title: "Precondition Failed", status: 412, detail: `${key} changed since it was read` } };
}
