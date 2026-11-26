// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike). The candidate renderer: one component,
// native inputs, no form library. It renders any FormDefinition and shows
// violations (the client's own and the server's) on the inputs they name.

import { useEffect, useId, useRef, type FormEvent } from "react";
import type { FormDefinition, Violation } from "./forms";
import { localize, text, type Locale } from "./messages";

export interface ConfiguredFormProps {
  definition: FormDefinition;
  values: Record<string, string>;
  violations: Violation[];
  locale: Locale;
  submitLabel: string;
  /** The state a `requiredToEnter` violation is about, for its message. */
  stateName?: string;
  onChange: (path: string, value: string) => void;
  onSubmit: () => void;
}

export function ConfiguredForm(props: ConfiguredFormProps) {
  const { definition, values, violations, locale } = props;
  const id = useId();
  const formRef = useRef<HTMLFormElement>(null);
  const byPath = new Map<string, Violation>();
  for (const v of violations) if (!byPath.has(v.field)) byPath.set(v.field, v);
  const known = new Set(definition.fields.map((f) => f.path));
  const orphans = violations.filter((v) => !known.has(v.field));
  const label = (path: string) => definition.fields.find((f) => f.path === path)?.label ?? path;

  // After a refused submit, focus lands on the first input that is wrong.
  useEffect(() => {
    if (violations.length > 0) formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus();
  }, [violations]);

  function submit(event: FormEvent) {
    event.preventDefault();
    props.onSubmit();
  }

  return (
    <form ref={formRef} aria-labelledby={`${id}-title`} noValidate onSubmit={submit} className="cf-form" data-form={definition.id}>
      <h2 id={`${id}-title`} className="cf-title">{definition.id}</h2>
      {violations.length > 0 && (
        <div role="alert" className="cf-summary">
          <p>{text(locale, "summary", { count: byPath.size + orphans.length })}</p>
          <ul>
            {[...byPath.values()].filter((v) => known.has(v.field)).map((v) => (
              <li key={v.field}><a href={`#${id}-${v.field}`}>{label(v.field)}</a>: {localize(locale, v, label(v.field), props.stateName)}</li>
            ))}
            {orphans.map((v) => <li key={v.field + v.message}>{v.message}</li>)}
          </ul>
        </div>
      )}
      {definition.fields.map((f) => {
        const inputId = `${id}-${f.path}`;
        const violation = byPath.get(f.path);
        const describedBy = [f.help ? `${inputId}-help` : "", violation ? `${inputId}-error` : ""].filter(Boolean).join(" ") || undefined;
        const common = {
          id: inputId,
          name: f.path,
          required: f.required,
          "aria-required": f.required,
          "aria-invalid": violation ? true : undefined,
          "aria-describedby": describedBy,
          value: values[f.path] ?? "",
        } as const;
        return (
          <div key={f.path} className="cf-field" data-path={f.path}>
            <label htmlFor={inputId}>
              {f.label}
              {f.required && <span className="cf-req"> ({locale === "it" ? "obbligatorio" : "required"})</span>}
            </label>
            {f.kind === "choice" ? (
              <select {...common} onChange={(e) => props.onChange(f.path, e.target.value)}>
                <option value="">{text(locale, "none")}</option>
                {(f.options ?? []).map((o) => <option key={o.key} value={o.key}>{o.name}</option>)}
              </select>
            ) : f.kind === "markdown" ? (
              <textarea {...common} rows={4} onChange={(e) => props.onChange(f.path, e.target.value)} />
            ) : (
              <input
                {...common}
                type={f.kind === "number" ? "number" : f.kind === "date" ? "date" : "text"}
                maxLength={f.maxLength}
                onChange={(e) => props.onChange(f.path, e.target.value)}
              />
            )}
            {f.help && <p id={`${inputId}-help`} className="cf-help">{f.help}</p>}
            {violation && <p id={`${inputId}-error`} className="cf-error">{localize(locale, violation, f.label, props.stateName)}</p>}
          </div>
        );
      })}
      <button type="submit" className="cf-submit">{props.submitLabel}</button>
    </form>
  );
}
