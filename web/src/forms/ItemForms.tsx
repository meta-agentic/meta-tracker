import { useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { ApiError, type WireItem } from "../api";
import { useModal } from "../components/useModal";
import { CloseIcon } from "../components/icons";
import { useWorkspaceStore } from "../store/workspaceStore";
import { ConfiguredForm } from "./ConfiguredForm";
import { enterForm, itemForm, promptFor, toBody, valuesOf, type Choice, type FormDefinition } from "./definition";
import { useItemWrites, type ItemWrites } from "./itemWritesContext";
import { check, created, defaultType, type Effective, type FieldViolation, type Fields } from "./rules";

/** The violations a refused write carries, and a sentence for a refusal that names no field. */
function refusal(error: unknown, t: (key: string) => string): { violations: FieldViolation[]; message: string | null } {
  if (error instanceof ApiError && error.problem) {
    if (error.status === 412) return { violations: [], message: t("form.stale") };
    if (error.problem.violations.length > 0) return { violations: error.problem.violations, message: null };
    return { violations: [], message: error.problem.detail ?? error.message };
  }
  return { violations: [], message: error instanceof Error ? error.message : String(error) };
}

/**
 * Items of the open workspace that an item of `type` may sit under, from what
 * the board already holds: each drawn with its type's icon, its title, and its
 * key after it.
 */
function useParentChoices(cfg: Effective | null, type: string): Choice[] {
  const issuesById = useWorkspaceStore((s) => s.issuesById);
  const epicsById = useWorkspaceStore((s) => s.epicsById);
  return useMemo(() => {
    const allowed = cfg?.itemTypes[type]?.parents ?? [];
    if (allowed.length === 0) return [];
    const rows = [
      ...Object.values(epicsById).map((e) => ({ key: e.key, title: e.title, type: "epic" })),
      ...Object.values(issuesById).map((i) => ({ key: i.key, title: i.title, type: i.type })),
    ];
    return rows
      .filter((r) => r.type !== null && allowed.includes(r.type))
      .sort((a, b) => a.key.localeCompare(b.key, undefined, { numeric: true }))
      .map((r) => ({ key: r.key, name: r.title, icon: cfg?.itemTypes[r.type as string]?.icon, detail: r.key }));
  }, [cfg, type, issuesById, epicsById]);
}

/** The read-only note a bound workspace shows where the write controls would be. */
export function ReadOnlyNote() {
  const { t } = useTranslation();
  return (
    <p className="vec-muted-note vec-form__readonly" role="note">
      {t("form.readOnly")}
    </p>
  );
}

/** "New item" on the board, or the reason there is none. Nothing until the workspace's writability is known. */
export function NewItemControl({ boardKey, columnKey }: { boardKey: string; columnKey: string | null }) {
  const { t } = useTranslation();
  const writes = useItemWrites();
  const [open, setOpen] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  if (!writes || writes.readOnly === null) return null;
  if (writes.readOnly) return <ReadOnlyNote />;
  if (!writes.configuration) return null;
  return (
    <>
      <button ref={opener} type="button" className="vec-button vec-button--primary" onClick={() => setOpen(true)}>
        {t("form.newItem")}
      </button>
      {open && (
        <NewItemSheet
          writes={writes}
          cfg={writes.configuration}
          boardKey={boardKey}
          columnKey={columnKey}
          onClose={() => {
            setOpen(false);
            opener.current?.focus();
          }}
        />
      )}
    </>
  );
}

function NewItemSheet({
  writes,
  cfg,
  boardKey,
  columnKey,
  onClose,
}: {
  writes: ItemWrites;
  cfg: Effective;
  boardKey: string;
  columnKey: string | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const rootRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  useModal(rootRef, panelRef, onClose);
  const initialType = defaultType(cfg) ?? Object.keys(cfg.itemTypes)[0];
  const [values, setValues] = useState(() => valuesOf("", created(cfg, { type: initialType })));
  const type = values["fields.type"] || initialType;
  const parents = useParentChoices(cfg, type);
  const definition = useMemo(() => itemForm(cfg, type, "create", parents), [cfg, type, parents]);
  const [violations, setViolations] = useState<FieldViolation[]>([]);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    panelRef.current?.querySelector<HTMLElement>("input, select, textarea")?.focus();
  }, []);

  function change(path: string, value: string) {
    if (path === "fields.type" && cfg.itemTypes[value]) {
      // A new type brings its own fields and defaults; a value it has no field for is dropped.
      const next = itemForm(cfg, value, "create");
      const kept = Object.fromEntries(Object.entries(values).filter(([p]) => next.fields.some((f) => f.path === p)));
      setValues({ ...valuesOf(null, created(cfg, { type: value })), ...kept, "fields.type": value });
    } else {
      setValues({ ...values, [path]: value });
    }
  }

  async function submit() {
    const body = toBody(definition, values, "create");
    const local = check(cfg, { op: "create", state: columnKey ?? "", title: body.title ?? "", fields: body.fields });
    setFormError(null);
    if (local.length > 0) return setViolations(local);
    setBusy(true);
    try {
      await writes.client.createItem({ board: boardKey, ...(columnKey ? { column: columnKey } : {}), title: body.title ?? "", fields: body.fields });
      writes.refresh();
      onClose();
    } catch (error) {
      const refused = refusal(error, t);
      setViolations(refused.violations);
      setFormError(refused.message);
    } finally {
      setBusy(false);
    }
  }

  return createPortal(
    <div className="vec-sheet-root" ref={rootRef}>
      <div className="vec-scrim" aria-hidden="true" onClick={onClose} />
      <aside ref={panelRef} className="vec-sheet" role="dialog" aria-modal="true" aria-labelledby="vec-new-item-title" tabIndex={-1}>
        <div className="vec-sheet__head">
          <h2 id="vec-new-item-title" className="vec-form__title">
            {t("form.newItem")}
          </h2>
          <button type="button" className="vec-icon-button vec-sheet__close" aria-label={t("detail.close")} onClick={onClose}>
            <CloseIcon />
          </button>
        </div>
        <div className="vec-sheet__body">
          <ConfiguredForm
            definition={definition}
            values={values}
            violations={violations}
            formError={formError}
            busy={busy}
            stateName={columnKey ? cfg.states[columnKey]?.name : undefined}
            submitLabel={t("form.create")}
            onChange={change}
            onSubmit={() => void submit()}
            onCancel={onClose}
          />
        </div>
      </aside>
    </div>,
    document.body,
  );
}

/**
 * Edit Item, in the item's detail sheet: the stored values, read again with
 * the version the write takes back. A 412 says someone else changed it and
 * loads what they wrote.
 */
export function EditItemForm({ itemKey, onDone }: { itemKey: string; onDone: () => void }) {
  const { t } = useTranslation();
  const writes = useItemWrites();
  const [item, setItem] = useState<WireItem | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [violations, setViolations] = useState<FieldViolation[]>([]);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cfg = writes?.configuration ?? null;
  const type = typeof item?.fields.type === "string" ? item.fields.type : (cfg && defaultType(cfg)) ?? "";
  const parents = useParentChoices(cfg, type);

  const client = writes?.client ?? null;
  async function load() {
    if (!client) return;
    const read = await client.getItem(itemKey);
    setItem(read);
    setValues(valuesOf(read.title, read.fields as Fields));
  }

  useEffect(() => {
    if (!client) return;
    let current = true;
    client.getItem(itemKey).then(
      (read) => {
        if (!current) return;
        setItem(read);
        setValues(valuesOf(read.title, read.fields as Fields));
      },
      (error: unknown) => current && setFormError(refusal(error, t).message),
    );
    return () => {
      current = false;
    };
  }, [client, itemKey, t]);

  if (!writes || !cfg) return null;
  if (!item) return <p className="vec-muted-note">{formError ?? t("form.loading")}</p>;
  const definition = itemForm(cfg, type, "edit", parents);
  const initial = valuesOf(item.title, item.fields as Fields);

  async function submit() {
    if (!writes || !cfg || !item) return;
    const body = toBody(definition, values, "edit", initial);
    const local = check(cfg, { op: "edit", item: { state: item.column, fields: item.fields as Fields }, ...body });
    setFormError(null);
    if (local.length > 0) return setViolations(local);
    setBusy(true);
    try {
      await writes.client.editItem(item.key, item.version, body);
      writes.refresh();
      onDone();
    } catch (error) {
      const refused = refusal(error, t);
      setViolations(refused.violations);
      setFormError(refused.message);
      if (error instanceof ApiError && error.status === 412) {
        await load();
        writes.refresh();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <ConfiguredForm
      definition={definition}
      values={values}
      violations={violations}
      formError={formError}
      busy={busy}
      stateName={cfg.states[item.column]?.name}
      submitLabel={t("form.save")}
      onChange={(path, value) => setValues({ ...values, [path]: value })}
      onSubmit={() => void submit()}
      onCancel={onDone}
    />
  );
}

/**
 * "Move to…" in the detail sheet. A state that needs fields the item lacks
 * opens a prompt for exactly those, sent with the move as one write; a server
 * `requiredToEnter` opens the same prompt. Cancel leaves the item where it was.
 */
export function MoveItemControl({ itemKey, columns, current }: { itemKey: string; columns: { key: string; name: string }[]; current: string }) {
  const { t } = useTranslation();
  const writes = useItemWrites();
  const [to, setTo] = useState("");
  const [prompt, setPrompt] = useState<{ item: WireItem; definition: FormDefinition } | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [violations, setViolations] = useState<FieldViolation[]>([]);
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cfg = writes?.configuration ?? null;
  if (!writes || !cfg) return null;

  async function send(item: WireItem, fields?: Fields) {
    if (!writes || !cfg) return;
    setBusy(true);
    try {
      await writes.client.moveItem(item.key, item.version, { column: to, ...(fields ? { fields } : {}) });
      setPrompt(null);
      setTo("");
      writes.refresh();
    } catch (error) {
      const refused = refusal(error, t);
      const entering = refused.violations.filter((v) => v.code === "requiredToEnter");
      if (!prompt && entering.length > 0) {
        setPrompt({ item, definition: promptFor(cfg, entering.map((v) => v.field)) });
        setViolations([]);
      } else {
        setViolations(refused.violations);
      }
      setFormError(refused.message);
    } finally {
      setBusy(false);
    }
  }

  async function move() {
    if (!writes || !cfg || !to) return;
    setFormError(null);
    try {
      const item = await writes.client.getItem(itemKey);
      const type = typeof item.fields.type === "string" ? item.fields.type : (defaultType(cfg) ?? "");
      const needed = enterForm(cfg, type, item.fields as Fields, to);
      if (needed) {
        setValues({});
        setViolations([]);
        setPrompt({ item, definition: needed });
      } else {
        await send(item);
      }
    } catch (error) {
      setFormError(refusal(error, t).message);
    }
  }

  function submitPrompt() {
    if (!prompt || !cfg) return;
    const body = toBody(prompt.definition, values, "create");
    const type = typeof prompt.item.fields.type === "string" ? prompt.item.fields.type : "";
    const local = check(cfg, {
      op: "move",
      item: { state: prompt.item.column, fields: { ...(prompt.item.fields as Fields), type } },
      to,
      fields: body.fields,
    });
    if (local.length > 0) return setViolations(local);
    void send(prompt.item, body.fields);
  }

  const toName = columns.find((c) => c.key === to)?.name ?? to;
  return (
    <div className="vec-form__move">
      <div className="vec-form__move-row">
        <label htmlFor={`vec-move-${itemKey}`}>{t("form.moveTo")}</label>
        <select
          id={`vec-move-${itemKey}`}
          className="vec-select"
          value={to}
          disabled={prompt !== null}
          onChange={(e) => setTo(e.target.value)}
        >
          <option value="">{t("form.chooseState")}</option>
          {columns
            .filter((c) => c.key !== current)
            .map((c) => (
              <option key={c.key} value={c.key}>
                {c.name}
              </option>
            ))}
        </select>
        {!prompt && (
          <button type="button" className="vec-button" disabled={!to || busy} onClick={() => void move()}>
            {t("form.move")}
          </button>
        )}
      </div>
      {!prompt && formError && (
        <p className="vec-form__error" role="alert">
          {formError}
        </p>
      )}
      {prompt && (
        <section className="vec-form__prompt" aria-label={t("form.promptTitle", { state: toName })}>
          <p className="vec-form__prompt-lead">{t("form.promptTitle", { state: toName })}</p>
          <ConfiguredForm
            definition={prompt.definition}
            values={values}
            violations={violations}
            formError={formError}
            busy={busy}
            stateName={toName}
            submitLabel={t("form.moveToState", { state: toName })}
            onChange={(path, value) => setValues({ ...values, [path]: value })}
            onSubmit={submitPrompt}
            onCancel={() => {
              setPrompt(null);
              setViolations([]);
              setFormError(null);
            }}
          />
        </section>
      )}
    </div>
  );
}
