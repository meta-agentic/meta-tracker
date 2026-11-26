import { useEffect, useId, useRef, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import type { FieldSpec, FormDefinition } from "./definition";
import { FieldIcon, FieldKindIcon } from "./FieldIcon";
import { FIELD_ICONS } from "./fieldIcons";
import type { FieldViolation } from "./rules";

export interface ConfiguredFormProps {
  definition: FormDefinition;
  values: Record<string, string>;
  /** The client's own and the server's, each on the input its `field` names; others go to the summary. */
  violations: FieldViolation[];
  submitLabel: string;
  /** The state a `requiredToEnter` violation is about, named in its message. */
  stateName?: string;
  /** A refusal that names no field: a conflict, a network failure. */
  formError?: string | null;
  busy?: boolean;
  onChange: (path: string, value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}

/** A choice drawn as icons alone: every option has an icon the client knows, and there are few. */
function iconChoice(field: FieldSpec): boolean {
  const options = field.options ?? [];
  return options.length > 0 && options.length <= 8 && options.every((o) => o.icon && FIELD_ICONS[o.icon]);
}

/**
 * Any form the configuration defines (VEC-70): one component for New Item,
 * Edit Item and the prompt a move shows. Native inputs on the shell's tokens;
 * every input has a bound label that says "required" in words, an invalid one
 * says why through `aria-describedby`, the summary links each problem to its
 * input, and after a refused submit focus lands on the first wrong input. An
 * option drawn as an icon alone keeps its name as its accessible name and its
 * tooltip. Each label starts with the glyph of its field's kind, decorative.
 */
export function ConfiguredForm(props: ConfiguredFormProps) {
  const { definition, values, violations } = props;
  const { t, i18n } = useTranslation();
  const id = useId();
  const formRef = useRef<HTMLFormElement>(null);

  const label = (f: FieldSpec) => f.label ?? t(`form.core.${f.path}`);
  const byPath = new Map<string, FieldViolation>();
  for (const v of violations) if (!byPath.has(v.field)) byPath.set(v.field, v);
  const known = new Set(definition.fields.map((f) => f.path));
  const elsewhere = violations.filter((v) => !known.has(v.field));
  const say = (v: FieldViolation, name: string) => {
    const key = `form.violation.${v.code}`;
    return v.code && i18n.exists(key) ? t(key, { label: name, state: props.stateName ?? "" }) : v.message;
  };
  const inputId = (path: string) => `${id}-${path}`;

  // After a refused submit, focus lands on the first input that is wrong.
  useEffect(() => {
    if (violations.length === 0) return;
    const first = formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]');
    (first ?? formRef.current?.querySelector<HTMLElement>(".vec-form__summary"))?.focus();
  }, [violations]);

  function submit(event: FormEvent) {
    event.preventDefault();
    props.onSubmit();
  }

  const problems = [...byPath.values()].filter((v) => known.has(v.field)).length + elsewhere.length;

  return (
    <form ref={formRef} className="vec-form" noValidate onSubmit={submit}>
      {(problems > 0 || props.formError) && (
        <div className="vec-form__summary" role="alert" tabIndex={-1}>
          {problems > 0 && <p>{t("form.summary", { count: problems })}</p>}
          <ul>
            {definition.fields
              .filter((f) => byPath.has(f.path))
              .map((f) => (
                <li key={f.path}>
                  <a href={`#${inputId(f.path)}`}>{label(f)}</a>: {say(byPath.get(f.path)!, label(f))}
                </li>
              ))}
            {elsewhere.map((v) => (
              <li key={`${v.field}:${v.message}`}>{v.message}</li>
            ))}
            {props.formError && <li>{props.formError}</li>}
          </ul>
        </div>
      )}
      {definition.fields.map((f) => {
        const violation = byPath.get(f.path);
        const name = label(f);
        const field = inputId(f.path);
        const describedBy =
          [f.help ? `${field}-help` : "", violation ? `${field}-error` : ""].filter(Boolean).join(" ") || undefined;
        const value = values[f.path] ?? "";
        const change = (next: string) => props.onChange(f.path, next);
        const required = f.required ? <span className="vec-form__required"> {t("form.required")}</span> : null;
        const after = (
          <>
            {f.help && (
              <p id={`${field}-help`} className="vec-form__help">
                {f.help}
              </p>
            )}
            {violation && (
              <p id={`${field}-error`} className="vec-form__error">
                {say(violation, name)}
              </p>
            )}
          </>
        );

        if (f.kind === "choice" && iconChoice(f)) {
          return (
            <fieldset
              key={f.path}
              id={field}
              className="vec-form__field vec-form__choices"
              data-path={f.path}
              aria-invalid={violation ? true : undefined}
              aria-describedby={describedBy}
              tabIndex={violation ? -1 : undefined}
            >
              <legend>
                <FieldKindIcon field={f} />
                {name}
                {required}
              </legend>
              <div className="vec-form__icons">
                {!f.required && (
                  <label className="vec-form__icon-option">
                    <input type="radio" name={field} value="" checked={value === ""} onChange={() => change("")} />
                    <span>{t("form.none")}</span>
                  </label>
                )}
                {(f.options ?? []).map((o) => (
                  <label key={o.key} className="vec-form__icon-option" title={o.name}>
                    <input
                      type="radio"
                      name={field}
                      value={o.key}
                      aria-label={o.name}
                      checked={value === o.key}
                      onChange={() => change(o.key)}
                    />
                    <FieldIcon name={o.icon} />
                  </label>
                ))}
              </div>
              {after}
            </fieldset>
          );
        }

        const common = {
          id: field,
          name: f.path,
          required: f.required,
          "aria-invalid": violation ? true : undefined,
          "aria-describedby": describedBy,
          value,
        } as const;
        return (
          <div key={f.path} className="vec-form__field" data-path={f.path}>
            <label htmlFor={field}>
              <FieldKindIcon field={f} />
              {name}
              {required}
            </label>
            {f.kind === "choice" ? (
              <select {...common} className="vec-form__input" onChange={(e) => change(e.target.value)}>
                <option value="">{t("form.none")}</option>
                {(f.options ?? []).map((o) => (
                  <option key={o.key} value={o.key}>
                    {o.name}
                  </option>
                ))}
              </select>
            ) : f.kind === "markdown" ? (
              <textarea {...common} className="vec-form__input" rows={5} onChange={(e) => change(e.target.value)} />
            ) : (
              <input
                {...common}
                className="vec-form__input"
                type={f.kind === "number" ? "number" : f.kind === "date" ? "date" : "text"}
                maxLength={f.maxLength}
                onChange={(e) => change(e.target.value)}
              />
            )}
            {after}
          </div>
        );
      })}
      <div className="vec-form__actions">
        <button type="submit" className="vec-button vec-button--primary" disabled={props.busy}>
          {props.submitLabel}
        </button>
        <button type="button" className="vec-button" onClick={props.onCancel}>
          {t("form.cancel")}
        </button>
      </div>
    </form>
  );
}
