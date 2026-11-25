// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike). Not product code; see ../item-forms.md.
//
// The form-definition contract: what the SPA derives from a workspace's
// effective configuration, and the item rules it mirrors from the server.

/** The effective configuration, only the members forms read. */
export interface Effective {
  template: string;
  states: Record<string, { name: string; category: string; requires?: string[] }>;
  stateOrder: string[];
  itemTypes: Record<string, { name: string; parents?: string[]; fields?: string[]; required?: string[] }>;
  fields: Record<string, FieldDecl>;
  settings: { defaultItemType: string };
}

export interface FieldDecl {
  name: string;
  description?: string;
  type: "number" | "text" | "date" | "markdown" | "choice";
  scale?: "fibonacci" | "linear";
  options?: Option[];
  default?: unknown;
}

export interface Option {
  key: string;
  name: string;
}

/** One input of a rendered form. `path` is the request member it writes and the violation field it answers to. */
export interface FieldSpec {
  path: string;
  label: string;
  kind: "text" | "markdown" | "number" | "date" | "choice";
  required: boolean;
  options?: Option[];
  /** The value is sent as a JSON number (a number field, or a choice on a point scale). */
  numeric?: boolean;
  pattern?: string;
  maxLength?: number;
  help?: string;
}

/** A form: what both VEC-10 (New Project) and VEC-70 (item forms) render. */
export interface FormDefinition {
  id: string;
  fields: FieldSpec[];
}

/** The closed set of violation codes the client localizes; `message` stays the English fallback. */
export type Code =
  | "required"
  | "requiredToEnter"
  | "tooLong"
  | "pattern"
  | "type"
  | "scale"
  | "option"
  | "unknownType"
  | "notOnType"
  | "parent"
  | "taken";

/** ADR-VEC-05's violation, plus the proposed optional `code`. */
export interface Violation {
  field: string;
  level: string;
  message: string;
  code?: Code;
}

export const TITLE_MAX = 500;
export const SCALES: Record<string, number[]> = {
  fibonacci: [0, 1, 2, 3, 5, 8, 13, 21],
  linear: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
};

/** Members Vectis owns in `fields`; a template may not declare them. */
export const CORE_FIELDS = ["type", "parentId"];

function fieldKind(decl: FieldDecl): FieldSpec["kind"] {
  if (decl.type === "number" && decl.scale) return "choice";
  return decl.type;
}

function scaleOptions(scale: string): Option[] {
  return SCALES[scale].map((n) => ({ key: String(n), name: String(n) }));
}

/** The declared fields an item type has, in form order. Absent `fields` means every declared field, by key. */
export function fieldsOf(cfg: Effective, type: string): string[] {
  const t = cfg.itemTypes[type];
  if (!t) return [];
  return t.fields ?? Object.keys(cfg.fields).sort();
}

function spec(cfg: Effective, key: string, required: boolean): FieldSpec {
  const decl = cfg.fields[key];
  const kind = fieldKind(decl);
  return {
    path: `fields.${key}`,
    label: decl.name,
    kind,
    required,
    options: decl.type === "choice" ? decl.options : decl.scale ? scaleOptions(decl.scale) : undefined,
    numeric: decl.type === "number" || undefined,
    help: decl.description,
  };
}

/**
 * New Item (`create`) or Edit Item (`edit`): the core members (title, type on
 * create, parent when the type has parent types), then the type's declared
 * fields. `parents` are the items the caller offers as parents; which items
 * qualify is checked again by the server.
 */
export function itemForm(cfg: Effective, type: string, mode: "create" | "edit", parents: Option[] = []): FormDefinition {
  const required = new Set(cfg.itemTypes[type]?.required ?? []);
  const core: FieldSpec[] = [{ path: "title", label: "Title", kind: "text", required: true, maxLength: TITLE_MAX }];
  if (mode === "create") {
    core.push({
      path: "fields.type",
      label: "Type",
      kind: "choice",
      required: true,
      options: Object.entries(cfg.itemTypes).map(([key, t]) => ({ key, name: t.name })),
    });
  }
  if ((cfg.itemTypes[type]?.parents ?? []).length > 0) {
    core.push({ path: "fields.parentId", label: "Parent", kind: "choice", required: false, options: parents });
  }
  return {
    id: `item.${mode}.${type}`,
    fields: [...core, ...fieldsOf(cfg, type).map((k) => spec(cfg, k, required.has(k)))],
  };
}

/**
 * What entering `to` still needs from this item: the state's `requires` that
 * its type has and that are empty. Null when nothing is missing, so the move
 * goes ahead without a prompt.
 */
export function enterForm(cfg: Effective, type: string, current: Record<string, unknown>, to: string): FormDefinition | null {
  const has = new Set(fieldsOf(cfg, type));
  const missing = (cfg.states[to]?.requires ?? []).filter((k) => has.has(k) && isEmpty(current[k]));
  if (missing.length === 0) return null;
  return { id: `item.enter.${to}`, fields: missing.map((k) => spec(cfg, k, true)) };
}

/** VEC-10's New Project form: fixed inputs, template options from `GET /templates`. */
export function projectForm(templates: { key: string; name: string }[]): FormDefinition {
  return {
    id: "workspace.create",
    fields: [
      { path: "template", label: "Template", kind: "choice", required: true, options: templates.map((t) => ({ key: t.key, name: t.name })) },
      { path: "key", label: "Key", kind: "text", required: true, pattern: "^[A-Z][A-Z0-9]{1,9}$", help: "2 to 10 capital letters and digits, such as WEB" },
      { path: "name", label: "Name", kind: "text", required: true, maxLength: 200 },
    ],
  };
}

/** `YYYY-MM-DD` naming a real day: 2027-02-30 is refused, as java.time refuses it. */
export function isCalendarDate(value: unknown): boolean {
  const m = typeof value === "string" ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
  if (!m) return false;
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3]);
}

export function isEmpty(v: unknown): boolean {
  return v === undefined || v === null || (typeof v === "string" && v.trim() === "");
}

// ---------------------------------------------------------------------------
// The rules: the mirror of what the server's command layer checks. Same
// inputs, same violations (field + code); conformance.json pins both sides.
// ---------------------------------------------------------------------------

export type Write =
  | { op: "create"; state: string; title?: string; fields?: Record<string, unknown> }
  | { op: "edit"; item: Stored; title?: string; fields?: Record<string, unknown> }
  | { op: "move"; item: Stored; to: string; fields?: Record<string, unknown> };

export interface Stored {
  state: string;
  title: string;
  fields: Record<string, unknown>;
}

const LEVEL = "workspace";

function v(field: string, code: Code, message: string): Violation {
  return { field, level: LEVEL, message, code };
}

/** RFC 7396 over the flat field map, enough for the prototype. */
export function patch(target: Record<string, unknown>, p: Record<string, unknown> | undefined): Record<string, unknown> {
  const out = { ...target };
  for (const [k, val] of Object.entries(p ?? {})) {
    if (val === null) delete out[k];
    else out[k] = val;
  }
  return out;
}

/** Applies the declared defaults to a create, as the server would. */
export function withDefaults(cfg: Effective, type: string, fields: Record<string, unknown>): Record<string, unknown> {
  const out = { ...fields };
  for (const k of fieldsOf(cfg, type)) {
    const d = cfg.fields[k].default;
    if (d !== undefined && !(k in out)) out[k] = d;
  }
  return out;
}

function checkValue(cfg: Effective, key: string, value: unknown, out: Violation[]) {
  const decl = cfg.fields[key];
  const path = `fields.${key}`;
  switch (decl.type) {
    case "number":
      if (typeof value !== "number" || !Number.isFinite(value)) out.push(v(path, "type", `${decl.name} is a number`));
      else if (decl.scale && !SCALES[decl.scale].includes(value))
        out.push(v(path, "scale", `${decl.name} is one of ${SCALES[decl.scale].join(", ")}`));
      break;
    case "text":
    case "markdown":
      if (typeof value !== "string") out.push(v(path, "type", `${decl.name} is text`));
      break;
    case "date":
      if (!isCalendarDate(value))
        out.push(v(path, "type", `${decl.name} is a date, YYYY-MM-DD`));
      break;
    case "choice":
      if (!decl.options?.some((o) => o.key === value))
        out.push(v(path, "option", `${decl.name} is one of ${(decl.options ?? []).map((o) => o.key).join(", ")}`));
      break;
  }
}

/**
 * Every violation of a write, in one list. Create checks everything; edit
 * checks only what it touches (so an item written before a configuration
 * change stays editable); move checks what the target state requires, after
 * the move's own field patch.
 */
export function check(cfg: Effective, w: Write): Violation[] {
  const out: Violation[] = [];
  const title = w.op === "create" ? (w.title ?? "") : w.op === "edit" ? w.title : undefined;
  if (title !== undefined) {
    if (title.trim() === "") out.push(v("title", "required", "an item has a title that is not blank"));
    else if ([...title].length > TITLE_MAX) out.push(v("title", "tooLong", `a title has at most ${TITLE_MAX} characters`));
  }

  const before = w.op === "create" ? {} : w.item.fields;
  let after = patch(before, w.fields);
  const type = (w.op === "create" ? (after.type ?? cfg.settings.defaultItemType) : before.type) as string;
  if (w.op === "create") {
    if (!cfg.itemTypes[type]) {
      out.push(v("fields.type", "unknownType", `this workspace has no item type '${String(type)}'`));
      return out;
    }
    after = withDefaults(cfg, type, { ...after, type });
  } else if (w.fields && "type" in w.fields) {
    out.push(v("fields.type", "type", "an item's type is set when it is created"));
  }

  const has = new Set(fieldsOf(cfg, type));
  const touched = w.op === "create" ? Object.keys(after) : Object.keys(w.fields ?? {});
  for (const k of touched) {
    if (CORE_FIELDS.includes(k) || !(k in cfg.fields)) continue; // core, or the open long tail
    if (!has.has(k)) {
      out.push(v(`fields.${k}`, "notOnType", `a ${cfg.itemTypes[type].name} has no ${cfg.fields[k].name}`));
      continue;
    }
    if (!isEmpty(after[k])) checkValue(cfg, k, after[k], out);
  }

  const state = w.op === "create" ? w.state : w.op === "move" ? w.to : w.item.state;
  const entering = w.op !== "edit";
  for (const k of cfg.itemTypes[type].required ?? []) {
    if ((entering || touched.includes(k)) && isEmpty(after[k]))
      out.push(v(`fields.${k}`, "required", `a ${cfg.itemTypes[type].name} has a ${cfg.fields[k].name}`));
  }
  for (const k of cfg.states[state]?.requires ?? []) {
    if (!has.has(k) || !isEmpty(after[k])) continue;
    if (entering || touched.includes(k))
      out.push(v(`fields.${k}`, "requiredToEnter", `${cfg.states[state].name} needs ${cfg.fields[k].name}`));
  }
  return out;
}

/**
 * The form's string values as a write body: `title` at the top, `fields.x`
 * into `fields`. On create an empty input is left out; on edit an input the
 * user emptied is sent as null, which removes the field (RFC 7396).
 */
export function toBody(def: FormDefinition, values: Record<string, string>, mode: "create" | "edit",
                       initial: Record<string, string> = {}): { title?: string; fields: Record<string, unknown> } {
  const body: { title?: string; fields: Record<string, unknown> } = { fields: {} };
  for (const f of def.fields) {
    const raw = values[f.path] ?? "";
    if (mode === "edit" && raw === (initial[f.path] ?? "")) continue;
    const value = raw.trim() === "" ? (mode === "edit" ? null : undefined) : f.numeric ? Number(raw) : raw;
    if (value === undefined) continue;
    if (f.path === "title") body.title = raw;
    else body.fields[f.path.slice("fields.".length)] = value;
  }
  return body;
}

/** The checks one form can make on its own, before the rules run: required, pattern, length. */
export function checkForm(def: FormDefinition, values: Record<string, string>): Violation[] {
  const out: Violation[] = [];
  for (const f of def.fields) {
    const raw = (values[f.path] ?? "").trim();
    if (f.required && raw === "") out.push(v(f.path, "required", `${f.label} is required`));
    else if (raw && f.pattern && !new RegExp(f.pattern).test(raw)) out.push(v(f.path, "pattern", `${f.label} does not have the expected form`));
    else if (raw && f.maxLength && [...raw].length > f.maxLength) out.push(v(f.path, "tooLong", `${f.label} is too long`));
  }
  return out;
}
