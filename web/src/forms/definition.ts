import { fieldsOf, SCALES, type Effective, type FieldDecl, type Fields } from "./rules";

/**
 * What a form shows, derived from the effective configuration (VEC-67's
 * contract): the core members Vectis gives every item, then the item type's
 * declared fields in its order. Nothing here is stored or sent; the request is
 * built from the values by `toBody`.
 */
export interface Choice {
  key: string;
  name: string;
  /** One of the shared icons; drawn alone, with `name` as its accessible name and tooltip. */
  icon?: string;
}

export interface FieldSpec {
  /** `title` or `fields.<key>`: the request member it writes, and the violation field it answers to. */
  path: string;
  /** The template's name for it, or null for a core member the form names in the reader's language. */
  label: string | null;
  kind: "text" | "markdown" | "number" | "date" | "choice";
  required: boolean;
  options?: Choice[];
  /** The value is sent as a JSON number: a number field, or a choice on a point scale. */
  numeric?: boolean;
  maxLength?: number;
  help?: string;
}

export interface FormDefinition {
  fields: FieldSpec[];
}

function spec(cfg: Effective, key: string, required: boolean): FieldSpec {
  const decl = (cfg.fields ?? {})[key] as FieldDecl;
  const scale = decl.type === "number" && decl.scale ? SCALES[decl.scale] : undefined;
  return {
    path: `fields.${key}`,
    label: decl.name,
    kind: scale ? "choice" : decl.type,
    required,
    options:
      decl.type === "choice"
        ? decl.options?.map((o) => ({ key: o.key, name: o.name, icon: o.icon }))
        : scale?.map((n) => ({ key: String(n), name: String(n) })),
    numeric: decl.type === "number" || undefined,
    help: decl.description,
  };
}

/**
 * New Item (`create`) or Edit Item (`edit`). The type is chosen on create
 * only; the parent picker shows when the type has parent types, offering
 * `parents`, which the server checks again.
 */
export function itemForm(cfg: Effective, type: string, mode: "create" | "edit", parents: Choice[] = []): FormDefinition {
  const required = new Set(cfg.itemTypes[type]?.required ?? []);
  const core: FieldSpec[] = [{ path: "title", label: null, kind: "text", required: true, maxLength: 500 }];
  if (mode === "create") {
    core.push({
      path: "fields.type",
      label: null,
      kind: "choice",
      required: true,
      options: Object.entries(cfg.itemTypes).map(([key, t]) => ({ key, name: t.name, icon: t.icon })),
    });
  }
  if ((cfg.itemTypes[type]?.parents ?? []).length > 0) {
    core.push({ path: "fields.parentId", label: null, kind: "choice", required: false, options: parents });
  }
  return { fields: [...core, ...fieldsOf(cfg, type).map((k) => spec(cfg, k, required.has(k)))] };
}

/**
 * What entering `to` still needs from an item: the state's `requires` its
 * type has and that are empty. Null when nothing is missing, so the move goes
 * straight through.
 */
export function enterForm(cfg: Effective, type: string, fields: Fields, to: string): FormDefinition | null {
  const has = new Set(fieldsOf(cfg, type));
  const missing = (cfg.states[to]?.requires ?? []).filter((k) => has.has(k) && isBlank(fields[k]));
  return missing.length === 0 ? null : { fields: missing.map((k) => spec(cfg, k, true)) };
}

/** The fields a server violation names, as an entering prompt: what `requiredToEnter` asks for. */
export function promptFor(cfg: Effective, paths: string[]): FormDefinition {
  return { fields: paths.filter((p) => p.startsWith("fields.")).map((p) => spec(cfg, p.slice(7), true)) };
}

function isBlank(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === "string" && value.trim() === "");
}

/** A stored item's values as the form's strings. */
export function valuesOf(title: string | null, fields: Fields): Record<string, string> {
  const out: Record<string, string> = {};
  if (title !== null) out.title = title;
  for (const [k, v] of Object.entries(fields)) {
    if (v !== null && v !== undefined && typeof v !== "object") out[`fields.${k}`] = String(v);
  }
  return out;
}

/**
 * The form's strings as a write body. On create an empty input is left out;
 * on edit only what changed is sent, and an input emptied is sent as null,
 * which removes the field (RFC 7396).
 */
export function toBody(
  def: FormDefinition,
  values: Record<string, string>,
  mode: "create" | "edit",
  initial: Record<string, string> = {},
): { title?: string; fields: Fields } {
  const body: { title?: string; fields: Fields } = { fields: {} };
  for (const f of def.fields) {
    const raw = values[f.path] ?? "";
    if (mode === "edit" && raw === (initial[f.path] ?? "")) continue;
    if (f.path === "title") {
      body.title = raw;
      continue;
    }
    const empty = raw.trim() === "";
    if (empty && mode === "create") continue;
    body.fields[f.path.slice("fields.".length)] = empty ? null : f.numeric ? Number(raw) : raw;
  }
  return body;
}
