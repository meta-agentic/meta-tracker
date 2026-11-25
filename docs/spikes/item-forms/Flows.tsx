// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike). The four forms wired to the mock
// server: New Project (VEC-10's shape), New Item, Edit Item, and the prompt a
// move shows when the target state needs a field the item lacks.

import { useMemo, useState } from "react";
import { ConfiguredForm } from "./ConfiguredForm";
import {
  check, checkForm, enterForm, itemForm, projectForm, toBody, withDefaults,
  type Effective, type Option, type Violation,
} from "./forms";
import type { MockServer, StoredItem } from "./mockServer";
import { text, type Locale } from "./messages";

function asStrings(fields: Record<string, unknown>, title?: string): Record<string, string> {
  const out: Record<string, string> = {};
  if (title !== undefined) out.title = title;
  for (const [k, v] of Object.entries(fields)) if (v !== undefined && v !== null) out[`fields.${k}`] = String(v);
  return out;
}

function parentOptions(cfg: Effective, server: MockServer, type: string): Option[] {
  const allowed = cfg.itemTypes[type]?.parents ?? [];
  return server.all().filter((i) => allowed.includes(i.fields.type as string)).map((i) => ({ key: i.key, name: `${i.key} ${i.title}` }));
}

export function NewItem(props: { cfg: Effective; server: MockServer; column: string; locale: Locale; onDone?: (item: StoredItem) => void }) {
  const { cfg, server, column, locale } = props;
  const initialType = cfg.settings.defaultItemType;
  const [values, setValues] = useState(() => asStrings(withDefaults(cfg, initialType, { type: initialType })));
  const [violations, setViolations] = useState<Violation[]>([]);
  const type = values["fields.type"] || initialType;
  const def = useMemo(() => itemForm(cfg, type, "create", parentOptions(cfg, server, type)), [cfg, server, type]);

  function change(path: string, value: string) {
    // A new type brings its own fields and defaults; values it does not have are dropped.
    if (path === "fields.type" && cfg.itemTypes[value]) {
      const next = itemForm(cfg, value, "create");
      const kept = Object.fromEntries(Object.entries(values).filter(([p]) => next.fields.some((f) => f.path === p)));
      setValues({ ...asStrings(withDefaults(cfg, value, {})), ...kept, "fields.type": value });
    } else {
      setValues({ ...values, [path]: value });
    }
  }

  function submit() {
    const body = toBody(def, values, "create");
    const local = [...checkForm(def, values), ...check(cfg, { op: "create", state: column, title: body.title, fields: body.fields })];
    if (local.length) return setViolations(local);
    const answer = server.create({ column, title: body.title ?? "", fields: body.fields });
    if ("item" in answer) {
      setViolations([]);
      props.onDone?.(answer.item);
    } else {
      setViolations(answer.problem.violations ?? [{ field: "", level: "workspace", message: answer.problem.detail }]);
    }
  }

  return <ConfiguredForm definition={def} values={values} violations={violations} locale={locale}
    stateName={cfg.states[column]?.name} submitLabel={text(locale, "create")} onChange={change} onSubmit={submit} />;
}

export function EditItem(props: { cfg: Effective; server: MockServer; itemKey: string; locale: Locale; onDone?: (item: StoredItem) => void }) {
  const { cfg, server, locale } = props;
  const item = server.get(props.itemKey)!;
  const type = item.fields.type as string;
  const def = useMemo(() => itemForm(cfg, type, "edit", parentOptions(cfg, server, type)), [cfg, server, type]);
  const initial = useMemo(() => asStrings(item.fields, item.title), [item]);
  const [values, setValues] = useState(initial);
  const [violations, setViolations] = useState<Violation[]>([]);

  function submit() {
    const body = toBody(def, values, "edit", initial);
    const local = [...checkForm(def, values), ...check(cfg, { op: "edit", item, ...body })];
    if (local.length) return setViolations(local);
    const answer = server.edit(item.key, item.version, body);
    if ("item" in answer) {
      setViolations([]);
      props.onDone?.(answer.item);
    } else {
      setViolations(answer.problem.violations ?? [{ field: "", level: "workspace", message: answer.problem.detail }]);
    }
  }

  return <ConfiguredForm definition={def} values={values} violations={violations} locale={locale}
    stateName={cfg.states[item.state]?.name} submitLabel={text(locale, "save")}
    onChange={(p, v) => setValues({ ...values, [p]: v })} onSubmit={submit} />;
}

/**
 * A move: straight through when the target state needs nothing the item
 * lacks, else a prompt for exactly the missing fields, sent with the move as
 * one write.
 */
export function MoveItem(props: { cfg: Effective; server: MockServer; itemKey: string; to: string; locale: Locale; onDone?: (item: StoredItem) => void }) {
  const { cfg, server, to, locale } = props;
  const item = server.get(props.itemKey)!;
  const prompt = enterForm(cfg, item.fields.type as string, item.fields, to);
  const [values, setValues] = useState<Record<string, string>>({});
  const [violations, setViolations] = useState<Violation[]>([]);
  const [moved, setMoved] = useState<StoredItem | null>(null);

  function send(fields?: Record<string, unknown>) {
    const answer = server.move(item.key, item.version, { column: to, fields });
    if ("item" in answer) {
      setMoved(answer.item);
      props.onDone?.(answer.item);
    } else {
      setViolations(answer.problem.violations ?? [{ field: "", level: "workspace", message: answer.problem.detail }]);
    }
  }

  if (moved) return <p role="status">{moved.key} → {cfg.states[moved.state].name}</p>;
  if (!prompt) {
    return <button type="button" onClick={() => send()}>{text(locale, "moveTo", { state: cfg.states[to].name })}</button>;
  }
  return <ConfiguredForm definition={prompt} values={values} violations={violations} locale={locale}
    stateName={cfg.states[to].name} submitLabel={text(locale, "moveTo", { state: cfg.states[to].name })}
    onChange={(p, v) => setValues({ ...values, [p]: v })}
    onSubmit={() => {
      const body = toBody(prompt, values, "create");
      const local = [...checkForm(prompt, values), ...check(cfg, { op: "move", item, to, fields: body.fields })];
      if (local.length) setViolations(local);
      else send(body.fields);
    }} />;
}

/** VEC-10's form on the same renderer: a fixed definition, template options from the API. */
export function NewProject(props: { templates: { key: string; name: string }[]; taken: string[]; locale: Locale; onDone?: (body: Record<string, string>) => void }) {
  const def = projectForm(props.templates);
  const [values, setValues] = useState<Record<string, string>>({});
  const [violations, setViolations] = useState<Violation[]>([]);
  function submit() {
    const local = checkForm(def, values);
    if (local.length) return setViolations(local);
    if (props.taken.includes(values.key)) {
      // The proposed 409: a taken key, with one violation naming the key field (ADR-VEC-05 amendment).
      return setViolations([{ field: "key", level: "workspace", code: "taken", message: `a workspace ${values.key} already exists` }]);
    }
    setViolations([]);
    props.onDone?.(values);
  }
  return <ConfiguredForm definition={def} values={values} violations={violations} locale={props.locale}
    submitLabel={text(props.locale, "create")} onChange={(p, v) => setValues({ ...values, [p]: v })} onSubmit={submit} />;
}
