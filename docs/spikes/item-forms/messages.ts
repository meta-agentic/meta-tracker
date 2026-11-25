// SPDX-License-Identifier: Apache-2.0
// THROWAWAY PROTOTYPE (VEC-67 spike). The SPA localizes violations by `code`;
// the server's `message` is the fallback for a code it does not know.
import type { Violation } from "./forms";

export type Locale = "en" | "it";

const TEXT: Record<Locale, Record<string, string>> = {
  en: {
    required: "{label} is required.",
    requiredToEnter: "{state} needs {label}.",
    tooLong: "{label} is too long.",
    pattern: "{label} does not have the expected form.",
    type: "{label} has the wrong kind of value.",
    scale: "{label} is a value on the point scale.",
    option: "Pick one of the options for {label}.",
    unknownType: "This workspace has no such item type.",
    notOnType: "This item type has no {label}.",
    parent: "This item cannot sit under the chosen parent.",
    taken: "{label} is already taken.",
    summary: "Fix {count} field(s) before saving.",
    none: "None",
    save: "Save",
    create: "Create",
    moveTo: "Move to {state}",
  },
  it: {
    required: "{label} è obbligatorio.",
    requiredToEnter: "{state} richiede {label}.",
    tooLong: "{label} è troppo lungo.",
    pattern: "{label} non ha la forma attesa.",
    type: "{label} ha un valore del tipo sbagliato.",
    scale: "{label} è un valore della scala dei punti.",
    option: "Scegli una delle opzioni per {label}.",
    unknownType: "Questo spazio di lavoro non ha questo tipo di elemento.",
    notOnType: "Questo tipo di elemento non ha {label}.",
    parent: "Questo elemento non può stare sotto il genitore scelto.",
    taken: "{label} è già in uso.",
    summary: "Correggi {count} campo/i prima di salvare.",
    none: "Nessuno",
    save: "Salva",
    create: "Crea",
    moveTo: "Sposta in {state}",
  },
};

export function text(locale: Locale, key: string, params: Record<string, string | number> = {}): string {
  const template = TEXT[locale][key] ?? key;
  return template.replace(/\{(\w+)\}/g, (_, name: string) => String(params[name] ?? ""));
}

/** A violation in the reader's language when its code is known, else the server's sentence. */
export function localize(locale: Locale, violation: Violation, label: string, state = ""): string {
  if (!violation.code || !TEXT[locale][violation.code]) return violation.message;
  return text(locale, violation.code, { label, state });
}
