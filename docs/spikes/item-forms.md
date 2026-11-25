# Configuration-driven forms (spike)

| | |
|---|---|
| **Status** | Proposed — the contract and the renderer are the PO's decisions (see *Decisions for the PO*) |
| **Type** | Spike (Core tier), VEC-67 |
| **Sprint** | VEC-S9 |
| **Requirement** | [ADR-VEC-01](../adr/ADR-VEC-01-product-requirements-and-features.md) **R-CORE-3** (workflows are data), **R-CORE-5** (template → instance); builds on [ADR-VEC-03](../adr/ADR-VEC-03-workflow-and-template-model.md) and [ADR-VEC-05](../adr/ADR-VEC-05-api-v1-conventions.md) |
| **Deliverables** | This note; a **throwaway** prototype in [`item-forms/`](./item-forms/) rendering New Project, New Item, Edit Item and a transition prompt from a Scrum and a stage-gate configuration, with the client's rules and a Java evaluator held to one conformance file |
| **Unblocks** | VEC-70 (item forms), VEC-10 (New Project form) |
| **Out of scope** | Product code, migrations, the built-in template versions themselves. Transition guards (`enterFrom`, WIP, gate approvals) are VEC-15's |

## Question and answer

> How does the effective configuration describe the forms, and how does the SPA render and validate them so that client and server agree?

**The configuration describes data rules, not forms.** Four small additions to the sections ADR-VEC-03 already has say what an item of each type holds and what entering a state needs: field kinds `markdown` and `choice` (with `options` and an optional `default`), `itemTypes.<t>.fields` and `itemTypes.<t>.required`, and `states.<s>.requires`. They inherit, override and lock like every other member. **The form is derived**, in the SPA, from the effective configuration the API already serves (`GET /api/v1/workspaces/{key}/configuration`): a `FormDefinition` of typed inputs, rendered by **one small component of our own** with native inputs. **The server is the authority**: the command layer checks every write against the same rules, whatever the channel, and answers ADR-VEC-05's 422 with one violation per field. **The client mirrors the rules** for feedback before a round trip, and the two are held together by one conformance file that both test suites read. The only change to the error shape is an optional `code` on a violation, so the SPA can say it in the reader's language.

## 1 · Today

- The template's `fields` section has `name`, `description`, `type` (`number`, `text`, `date`) and `scale` (`fibonacci`, `linear`). Nothing says which item type has which field, nothing is required, and there are no options or defaults (`template.schema.json`).
- An item's type is `fields.type` and its parent `fields.parentId`, both in the open `fields` document (VEC-45; the SPA's adapter reads them there).
- VEC-16's command layer checks the title, the rank and that field names are not blank. It does not read the configuration: any `fields.type`, any value in any field, is accepted.
- `POST …/items/{item}/move` takes `{column, rank?}` only.
- A violation is `{field, level, message}` with an English `message`; the SPA reads `detail` but not `violations` yet.
- The server's CSP is `script-src 'self'`, with no `'unsafe-eval'`.
- Built-in templates are `base`, `scrum`, `kanban` and `hybrid`. Stage-gate exists only as a test fixture of the hierarchy (VEC-66).

## 2 · The form-definition contract

### 2.1 What a template says (authored, inherited)

| Path | Meaning | Merge, lock |
|---|---|---|
| `fields.<k>.type` | adds `markdown` (multi-line text, shown as Markdown) and `choice` to `number`, `text`, `date` | scalar, replaced; lockable as `fields.<k>.type` |
| `fields.<k>.options` | for a `choice`: `[{key, name}]` in display order; the stored value is the key | array, replaced whole (ADR-VEC-03 D3) |
| `fields.<k>.default` | a value the server writes on create when the request leaves the field out; the form shows it filled in | scalar, replaced |
| `itemTypes.<t>.fields` | the declared fields an item of this type has, in form order; absent means every declared field, by key, so today's templates read unchanged | array, replaced whole |
| `itemTypes.<t>.required` | the subset of those fields an item of this type always has a value for | array, replaced whole |
| `states.<s>.requires` | fields an item must have a value for to enter `s`; applies only to items whose type has the field | array, replaced whole |

Resolution adds these checks, with the dotted path as the violation's field, as today: `options` only on a `choice`, at least one, keys unique and in the key format; a `default` valid for its field; every key in `fields`, `required` and `requires` names a declared field; `required` is a subset of `fields`; a field may not be declared as `type` or `parentId` (the core members below); the lock pattern accepts the new members. Requiredness lives on the item type, not on the field, so "a bug always has a description" does not force one on a task.

Worked example, as the deltas the prototype's two configurations carry over today's documents (the effective documents are in [`item-forms/templates/`](./item-forms/templates/)). The Scrum one also puts `requires` on In Progress, to show the rule on a second methodology; decision 7 recommends leaving that out of a built-in.

```json
{"states": {"gate-1": {"requires": ["businessCase"]}},
 "itemTypes": {"project": {"fields": ["description", "businessCase", "risk", "targetLaunch"]},
               "milestone": {"fields": ["targetLaunch"], "required": ["targetLaunch"]}},
 "fields": {"businessCase": {"name": "Business case", "type": "markdown"},
            "risk": {"name": "Risk", "type": "choice", "options": [{"key": "low", "name": "Low"}, {"key": "medium", "name": "Medium"}, {"key": "high", "name": "High"}]},
            "targetLaunch": {"name": "Target launch", "type": "date"}}}
```

```json
{"states": {"in-progress": {"requires": ["storyPoints"]}},
 "itemTypes": {"story": {"fields": ["description", "storyPoints", "priority"]},
               "bug": {"fields": ["description", "storyPoints", "priority"], "required": ["description"]},
               "task": {"fields": ["description"]}, "epic": {"fields": ["description", "priority"]}},
 "fields": {"description": {"name": "Description", "type": "markdown"},
            "priority": {"name": "Priority", "type": "choice", "options": [{"key": "high", "name": "High"}, {"key": "medium", "name": "Medium"}, {"key": "low", "name": "Low"}], "default": "medium"}}}
```

A gate's decision (approvals; go, recycle or kill) is not a form field: it is VEC-15's decision record. What a gate needs from the item before it can be decided is a `requires` on the gate state, as `gate-1` shows.

### 2.2 Core members (Vectis's, in every form)

`title` (required, at most 500 characters), `fields.type` (a choice of the workspace's item types, `settings.defaultItemType` first; set on create only) and `fields.parentId` (a choice of items whose type is in `itemTypes.<t>.parents`; shown only when the type has parent types). Declared fields follow, in `itemTypes.<t>.fields` order. Undeclared members of `fields` (labels, links, anything an import brought) stay the open long tail: they pass validation and no form shows them.

### 2.3 What the SPA derives (not stored, not on the wire)

```ts
interface FieldSpec {
  path: string;              // "title" | "fields.<key>": the request member it writes, and the violation field it answers to
  label: string;             // the template's name for the field
  kind: "text" | "markdown" | "number" | "date" | "choice";
  required: boolean;
  options?: { key: string; name: string }[];  // a choice, or the values of a point scale
  numeric?: boolean;         // sent as a JSON number
  pattern?: string; maxLength?: number; help?: string;
}
interface FormDefinition { id: string; fields: FieldSpec[] }
```

Three derivations, each a few lines over the effective document ([`forms.ts`](./item-forms/forms.ts)): `itemForm(cfg, type, "create" | "edit")`; `enterForm(cfg, type, item, toState)`, which returns only the `requires` of the target state that this item's type has and that are empty, or null when the move needs no prompt; and `projectForm(templates)`, VEC-10's fixed definition. A number field with a `scale` renders as a choice of the scale's values. The point scales are `fibonacci` 0, 1, 2, 3, 5, 8, 13, 21 and `linear` 0 to 10 in the prototype (a decision below).

The form is derived on the client rather than served as a `forms` resource because the effective document already holds everything the derivation needs, the SPA already reads it with its revision as ETag, and a second resource would be a second thing to keep in step with the configuration. If agents or other clients come to need ready-made forms, the same derivation can be served later without changing the vocabulary.

## 3 · The renderer

**Recommended: our own component** ([`ConfiguredForm.tsx`](./item-forms/ConfiguredForm.tsx), about 100 lines): native `input`, `select` and `textarea`, styled with the VEC-55 tokens, no form library. It renders any `FormDefinition` and puts each violation on the input it names.

| | Own renderer (prototype) | react-jsonschema-form + ajv8 | JSON Forms | react-hook-form + zod | Bespoke components per methodology |
|---|---|---|---|---|---|
| Bundle (minified + gzip, React external) | **3.4 KB**, measured | **104 KB**, measured (`@rjsf/core` + `@rjsf/validator-ajv8` 5.24); the SPA's whole bundle is 180 KB | not measured; validates with ajv like rjsf | not measured | small, but one per methodology |
| Runs under our CSP (`script-src 'self'`) | yes | **no**: ajv compiles schemas with `new Function`; precompiled validators need the schema at build time, and ours are runtime data | **no**, same ajv | yes | yes |
| One rule set with the server | the declarative rules, mirrored and pinned by the conformance file | per-type and per-transition JSON Schemas generated from the configuration; the server would need a JSON Schema validator to agree, and ajv's messages are English unless ajv-i18n is added | as rjsf | rules written a second time in zod, mirrored the same way as ours | none: the forms are code, which R-CORE-3 rules out |
| Look and accessibility | ours to build, on our tokens | a theme to write over its markup | renderer sets to adapt | ours to build | ours to build |

The cost accepted: we own the component's accessibility and the handful of input kinds. The closed set of kinds is what keeps that small; a need for conditional fields, repeating groups or rich widgets would reopen the choice.

Accessibility, as the prototype does it: every input has a `label` bound by `for`; "required" is written in the label, not only marked; `aria-required`, `aria-invalid` and `aria-describedby` point at the help and the error; a summary with `role="alert"` lists each problem with a link to its input; after a refused submit, focus moves to the first wrong input (test F4). It has not been checked with a screen reader.

Labels and i18n: field, type, state and option names are template data in the template's language, as the board's column names already are. The SPA localizes its own words and the violation messages, by `code` (en and it in the prototype). Per-locale names in the template are an additive member for later.

## 4 · Validation: who checks what

**The server is authoritative.** The rules live in the command module (VEC-16's `ItemRules` grows a configuration-aware part), so the SPA, an agent and a script get the same answer. The SPA runs the same rules before it sends, for feedback without a round trip; it never relies on them.

| Rule | Client (before submit) | Server (command layer) |
|---|---|---|
| title present, at most 500 | yes | yes |
| type is one of the workspace's; set on create only | yes | yes |
| a declared field belongs to the item's type | yes | yes |
| value fits its kind: number, date (a real day), choice key, point on the scale | yes | yes |
| `itemTypes.<t>.required` | yes | yes |
| `states.<s>.requires` on create into, or move into, `s` | yes | yes |
| defaults written on create | shown pre-filled | applied |
| the parent exists and its type may hold this one | offers only such items | yes (only the server knows the items) |
| version (`If-Match`), read-only workspace, a taken key | no | yes (412, 409) |

When a rule applies: a **create** is checked whole. An **edit** is checked on the members it touches, so an item written before a rule existed can still be renamed, and a touched field cannot be emptied if its type requires it or the item's current state does (cases C10, C11, C23, C24). A **move** checks the target state's `requires` against the item after the move's own field patch.

**A move carries the fields its target needs.** `POST …/items/{item}/move` takes an optional `fields` merge patch, applied in the same command: one version, one event, no window in which the edit landed and the move did not. The prompt sends exactly what `enterForm` asked for. A drag on the board sends the move without fields; if the server answers 422 with `requiredToEnter` violations, the SPA opens the same prompt from them.

**Keeping two evaluators equal.** [`conformance.json`](./item-forms/conformance.json) lists writes and the exact `[field, code]` pairs they must produce. The prototype runs it through `forms.ts` (Vitest) and through [`Conformance.java`](./item-forms/Conformance.java), a stand-in for the command layer: 24 of 24 agree. In the product the file lives once, in the command module's test resources, and the web suite reads it by relative path. It earned its place in the spike: `Date.parse("2027-02-30")` succeeds in JavaScript while `LocalDate.parse` refuses it, and case C22 failed on the client until the date check was made strict.

**Configuration drift.** The form is rendered from the configuration at some revision; the server checks against the current one. A 422 naming a type or field the form does not know makes the SPA re-read the configuration (ETag) and render again.

### 4.1 The error shape it needs

ADR-VEC-05's problem document stands: 422 with `violations: [{field, level, message}]`. Two additions:

1. **`code`**, optional, from a closed set: `required`, `requiredToEnter`, `tooLong`, `pattern`, `type`, `scale`, `option`, `unknownType`, `notOnType`, `parent`, and for VEC-10 `taken`. The SPA localizes by code and falls back to `message`, so an older client or a new code still shows the English sentence. Resolver violations keep no code until they need one.
2. **`field` is the request member's path**: `title`, `fields.storyPoints`, `fields.parentId`; for VEC-10 `template`, `key`, `name`. A violation whose path no input has goes to the form's summary.

A taken workspace key stays a 409 (ADR-VEC-05), and carries one violation `{field: "key", code: "taken"}` so the form can put it beside the key; ADR-VEC-05 today reserves `violations` for 422, so this is part of the amendment.

## 5 · What VEC-10 and VEC-70 build on

- **Both:** `FormDefinition`, `ConfiguredForm`, the violation `code` and path convention, and the SPA's mapping from a problem document to field errors (the SPA's `Problem` type gains `violations`).
- **VEC-10 (New Project):** `projectForm` with the template choice from `GET /api/v1/templates`, the key pattern `[A-Z][A-Z0-9]{1,9}` (the domain's), and the name; the preview of columns and item types reads `GET /templates/{key}/versions/{v}`. `POST /api/v1/workspaces` answers 422 on `template`, `key`, `name` and 409 `taken` on `key`. Nothing in the template vocabulary is needed for it.
- **VEC-70 (item forms):** the vocabulary in §2.1 with its resolver checks, the server rules in §4, `move` with `fields`, the SPA's configuration read, New Item, Edit Item and the transition prompt; at least one built-in that declares the new members (see the decisions).

## 6 · The prototype

Throwaway, like the other spikes' prototypes: it is not wired into `web/` and nothing imports it. It borrows `web/node_modules` through a symlink.

```
cd web && npm ci && cd ../docs/spikes/item-forms
./run.sh test   # Vitest: 36 cases
./run.sh java   # the conformance cases through the Java evaluator: 24/24
./run.sh demo   # the forms in a browser, http://localhost:5299
```

| Cases | What they show |
|---|---|
| F1–F6 | New Item and Edit Item from Scrum and from stage-gate: the fields per type, a point scale as a choice, a default filled in, a type switch dropping fields, a date and a Markdown input, a type's required field refused with focus on it, an edit saved at version 2, a violation localized in Italian |
| T1–T3 | the field required on one transition only: Scoping → Gate 1 prompts for the business case alone and sends it with the move; Idea → Scoping prompts for nothing; in Scrum, To Do → In Progress asks a story for points and lets a task through |
| V1–V3 | a rule only the server can check (the parent) lands on its input; VEC-10's form on the same renderer, with a key pattern and a taken key beside the key; every form path is one the rules use |
| C1–C24 | the conformance file through the client's rules (the Java run covers the same 24) |

Mutation checks on the client's rules, each caught: a loose date check fails C22; dropping `requiredToEnter` fails F6, C5, C7, C10 and C17; checking a type's required fields on every edit failed nothing until C23 was added, and now fails it.

## 7 · Not covered, and reopen if

- Per-transition requirements (`from` → `to`): ADR-VEC-03 has no authored transitions, only `enterFrom`, so requirements hang on the entered state. Reopen when a state entered from two sources needs different inputs.
- Conditional fields, repeating groups, rich widgets (user pickers before VEC-35 exists): each would reopen the renderer choice.
- Changing an item's type after creation: it changes the field set; refused for now (case C14).
- Per-locale names in templates; a served `forms` resource for agents.
- Authoring the new members in a UI (VEC-71) and writing them into a workspace delta (VEC-93).

## Decisions for the PO

Each with a recommendation; they can be taken one by one. 1–4 shape VEC-70's server part, 5 its UI, 6–7 what can be demonstrated.

1. **Where the form rules live.** Recommended: the four additions to existing sections (§2.1), forms derived on the client. Alternatives: a separate `forms` section that lays out forms apart from the data rules (a second thing to keep consistent with `fields`); authored JSON Schemas per type (ties the vocabulary to one validator's dialect).
2. **Requiredness per item type** (`itemTypes.<t>.required`, recommended) or per field (`fields.<k>.required`, simpler but the same for every type).
3. **Transition requirements on entering a state** (`states.<s>.requires`, recommended) or per transition, which needs authored transitions.
4. **The error shape:** add the optional `code` and allow a field violation on VEC-10's 409, as an ADR-VEC-05 amendment (recommended); or keep English messages only and localize nothing the server says.
5. **The renderer:** our own component (recommended, 3.4 KB, CSP-safe), or react-hook-form with zod if the PO prefers a library's form state; rjsf and JSON Forms are out under our CSP.
6. **Move carries fields** atomically (recommended), or the prompt edits first and moves second (two versions, two events, a window between them).
7. **Built-ins.** Recommended: ship stage-gate as a built-in (it is only a test fixture today) with `requires` on Gate 1, and a `scrum@2` with per-type field lists, a description and a priority but no `requires`, since needing points before work starts is a team's choice, not Scrum's. Also confirm the point scales' values (§2.3), which VEC-28 will read too.
