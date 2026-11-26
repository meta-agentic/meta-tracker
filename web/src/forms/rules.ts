/**
 * The rules a workspace's effective configuration sets on an item's fields,
 * as the server's command layer applies them (vectis-command `FieldRules` and
 * `ItemRules.title`). The client runs them before it sends, for an answer
 * without a round trip; the server stays the authority. Both sides run the
 * fixture `vectis-command/src/test/resources/conformance/item-rules.json`, so
 * they give the same violations in the same order.
 *
 * When each rule applies: a create is checked whole; an edit only on the
 * fields it changes (an item from before a rule stays editable elsewhere), and
 * a field it changes cannot be emptied if its type or its state needs it; a
 * move checks what the target state needs, after its own field patch.
 */

export type FieldKind = "number" | "text" | "date" | "markdown" | "choice";

export interface OptionDecl {
  key: string;
  name: string;
  icon?: string;
}

export interface FieldDecl {
  name: string;
  description?: string;
  type: FieldKind;
  scale?: "fibonacci" | "linear";
  options?: OptionDecl[];
  default?: unknown;
}

export interface ItemTypeDecl {
  name: string;
  description?: string;
  parents?: string[];
  icon?: string;
  fields?: string[];
  required?: string[];
}

export interface StateDecl {
  name: string;
  category: string;
  requires?: string[];
}

/** The effective configuration, as far as forms read it. */
export interface Effective {
  states: Record<string, StateDecl>;
  stateOrder?: string[];
  itemTypes: Record<string, ItemTypeDecl>;
  fields?: Record<string, FieldDecl>;
  settings?: { defaultItemType?: string };
}

/** A violation as the server's problem document carries it (ADR-VEC-05). */
export interface FieldViolation {
  field: string;
  message: string;
  code: string | null;
}

export const TITLE_MAX = 500;

export const SCALES: Record<string, number[]> = {
  fibonacci: [0, 1, 2, 3, 5, 8, 13, 21],
  linear: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
};

/** Members of `fields` Vectis keeps on every item; no template declares them. */
export const CORE_FIELDS = ["type", "parentId"];

export type Fields = Record<string, unknown>;

export type Write =
  | { op: "create"; state: string; title?: string | null; fields?: Fields | null }
  | { op: "edit"; item: { state: string; fields: Fields }; title?: string | null; fields?: Fields | null }
  | { op: "move"; item: { state: string; fields: Fields }; to: string; fields?: Fields | null };

function violation(field: string, code: string, message: string): FieldViolation {
  return { field, code, message };
}

export function isEmpty(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === "string" && value.trim() === "");
}

/** `YYYY-MM-DD` naming a real day: 2027-02-30 is refused, as java.time refuses it. */
export function isCalendarDate(value: unknown): boolean {
  const m = typeof value === "string" ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(value) : null;
  if (!m) return false;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const date = new Date(Date.UTC(y, mo - 1, d));
  return date.getUTCFullYear() === y && date.getUTCMonth() === mo - 1 && date.getUTCDate() === d;
}

/** RFC 7396 over the open fields: null removes, an object merges into an object, anything else replaces. */
export function mergePatch(target: Fields, patch: Fields | null | undefined): Fields {
  const out: Fields = { ...target };
  for (const [key, value] of Object.entries(patch ?? {})) {
    if (value === null) delete out[key];
    else if (typeof value === "object" && !Array.isArray(value)) {
      const base = out[key];
      out[key] = mergePatch(
        typeof base === "object" && base !== null && !Array.isArray(base) ? (base as Fields) : {},
        value as Fields,
      );
    } else out[key] = value;
  }
  return out;
}

export function defaultType(cfg: Effective): string | undefined {
  return cfg.settings?.defaultItemType;
}

/** The declared fields of a type, in form order: its list, or every declared field by key. */
export function fieldsOf(cfg: Effective, type: string): string[] {
  const listed = cfg.itemTypes[type]?.fields;
  return listed ?? Object.keys(cfg.fields ?? {}).sort();
}

/** What a value of the field may not be: `type`, `scale` or `option`, or null when it fits. */
export function valueProblem(decl: FieldDecl, value: unknown): string | null {
  switch (decl.type) {
    case "number":
      if (typeof value !== "number" || !Number.isFinite(value)) return "type";
      return decl.scale && !SCALES[decl.scale]?.includes(value) ? "scale" : null;
    case "text":
    case "markdown":
      return typeof value === "string" ? null : "type";
    case "date":
      return isCalendarDate(value) ? null : "type";
    case "choice":
      return decl.options?.some((o) => o.key === value) ? null : "option";
    default:
      return "type";
  }
}

/** The fields a create stores: the patch, its type (the default if none), and the declared defaults. */
export function created(cfg: Effective, patch: Fields | null | undefined): Fields {
  const out = mergePatch({}, patch);
  const type = typeof out.type === "string" ? out.type : defaultType(cfg);
  out.type = type;
  for (const key of fieldsOf(cfg, type ?? "")) {
    const d = cfg.fields?.[key]?.default;
    if (d !== undefined && d !== null && !(key in out)) out[key] = d;
  }
  return out;
}

function kind(decl: FieldDecl): string {
  switch (decl.type) {
    case "number":
      return decl.scale ? `is one of [${SCALES[decl.scale]?.join(", ")}]` : "is a number";
    case "date":
      return "is a date, YYYY-MM-DD";
    case "choice":
      return `is one of [${(decl.options ?? []).map((o) => o.key).join(", ")}]`;
    default:
      return "is text";
  }
}

function values(cfg: Effective, type: string, after: Fields, touched: string[], out: FieldViolation[]) {
  const declared = cfg.fields ?? {};
  const has = fieldsOf(cfg, type);
  for (const key of [...new Set(touched)].sort()) {
    if (CORE_FIELDS.includes(key) || !(key in declared)) continue;
    const decl = declared[key];
    if (!has.includes(key)) {
      out.push(violation(`fields.${key}`, "notOnType", `a ${cfg.itemTypes[type]?.name ?? type} has no ${decl.name}`));
      continue;
    }
    const problem = isEmpty(after[key]) ? null : valueProblem(decl, after[key]);
    if (problem) out.push(violation(`fields.${key}`, problem, `${decl.name} ${kind(decl)}`));
  }
}

function required(cfg: Effective, type: string, after: Fields, touched: string[] | null, out: FieldViolation[]) {
  for (const key of cfg.itemTypes[type]?.required ?? []) {
    if ((touched === null || touched.includes(key)) && isEmpty(after[key])) {
      out.push(violation(`fields.${key}`, "required", `a ${cfg.itemTypes[type]?.name ?? type} has a ${cfg.fields?.[key]?.name ?? key}`));
    }
  }
}

function requires(cfg: Effective, type: string, state: string, after: Fields, touched: string[] | null, out: FieldViolation[]) {
  const st = cfg.states[state];
  const has = fieldsOf(cfg, type);
  for (const key of st?.requires ?? []) {
    if (has.includes(key) && (touched === null || touched.includes(key)) && isEmpty(after[key])) {
      out.push(violation(`fields.${key}`, "requiredToEnter", `${st.name} needs ${cfg.fields?.[key]?.name ?? key}`));
    }
  }
}

function title(value: string | null | undefined, out: FieldViolation[]) {
  if (value === null || value === undefined || value.trim() === "") {
    out.push(violation("title", "required", "an item has a title that is not blank"));
  } else if ([...value].length > TITLE_MAX) {
    out.push(violation("title", "tooLong", `a title has at most ${TITLE_MAX} characters`));
  }
}

function typeChange(before: Fields, patch: Fields | null | undefined, out: FieldViolation[]) {
  if (patch && "type" in patch && patch.type !== before.type) {
    out.push(violation("fields.type", "type", "an item's type is set when it is created"));
  }
}

/** Every violation of a write, in the order the server lists them. */
export function check(cfg: Effective, w: Write): FieldViolation[] {
  const out: FieldViolation[] = [];
  if (w.op === "create") {
    title(w.title, out);
    const asked = mergePatch({}, w.fields);
    const type = "type" in asked ? asked.type : defaultType(cfg);
    if (typeof type !== "string" || !cfg.itemTypes[type]) {
      out.push(violation("fields.type", "unknownType", `this workspace has no item type '${String(type)}'`));
      return out;
    }
    const after = created(cfg, w.fields);
    values(cfg, type, after, Object.keys(after), out);
    required(cfg, type, after, null, out);
    requires(cfg, type, w.state, after, null, out);
    return out;
  }
  if (w.op === "edit") {
    if (w.title !== undefined && w.title !== null) title(w.title, out);
    const patch = w.fields ?? {};
    if (Object.keys(patch).length === 0) return out;
    typeChange(w.item.fields, patch, out);
    const type = typeof w.item.fields.type === "string" ? w.item.fields.type : (defaultType(cfg) ?? "");
    const after = mergePatch(w.item.fields, patch);
    const touched = Object.keys(patch);
    values(cfg, type, after, touched, out);
    required(cfg, type, after, touched, out);
    requires(cfg, type, w.item.state, after, touched, out);
    return out;
  }
  typeChange(w.item.fields, w.fields, out);
  const type = typeof w.item.fields.type === "string" ? w.item.fields.type : (defaultType(cfg) ?? "");
  const after = mergePatch(w.item.fields, w.fields);
  values(cfg, type, after, Object.keys(w.fields ?? {}), out);
  requires(cfg, type, w.to, after, null, out);
  return out;
}
