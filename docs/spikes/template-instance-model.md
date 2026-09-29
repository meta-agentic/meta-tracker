# Template → instance model (spike) — draft ADR-03: workflow and template model

| | |
|---|---|
| **Status** | Proposed |
| **Type** | Spike (Core tier), VEC-45 — written to be renamed `docs/adr/ADR-03-workflow-and-template-model.md` |
| **Sprint** | VEC-S4 |
| **Requirement** | [ADR-01](../adr/ADR-01-product-requirements-and-features.md) **R-CORE-5** (template → instance), **R-CORE-3** (workflows are data) |
| **Deliverable** | This decision + a throwaway resolution prototype in [`template-instance-model/`](./template-instance-model/). No production code, no migration on `main`. |
| **Unblocks** | VEC-10 (provisioning). Informs VEC-15 (workflow engine), VEC-28 (point scale), VEC-11 (re-keying), VEC-54 (backlog import). |

## Context

R-CORE-5:

> A workspace is provisioned from a **template** (Kanban, Scrum) and lives on as a **custom instance**: the instance references its template and carries only its overrides (states, item types, fields, policies). Template evolution is inheritable; instance deltas are explicit and reviewable.

Nothing represents that today: `workspace` has no configuration and there is no template table. The VEC-10 draft branch provisions by **copying** columns into `board_column`, with no link back to the template, a choice made so as not to pre-empt this decision.

## Decision

A **template** is an immutable, versioned JSON document stored as a row. A **workspace** stores a pinned reference to one template version and a **delta**, an [RFC 7396](https://www.rfc-editor.org/rfc/rfc7396) JSON Merge Patch over the template, in `jsonb`. The **effective configuration** is the merge, resolved on read. Elements have stable **keys** and merge by key; a state the instance adds is placed by a sparse **`after`** anchor, so adding a column never freezes the template's order. Template evolution arrives through an explicit, previewable **upgrade**. `board_column` rows become a **projection** of the effective states. Every state has one of four categories; the fourth, **`DISCONTINUED`**, is a terminal end state distinct from `DONE`, and both built-in templates end in a `no-go` state of that category that is kept off the board by default. The workflow engine (VEC-15) enforces **the same document**.

### 1 · Storage — template is a row; instance is reference + delta

- **Template = row** in `workspace_template (id, version, document jsonb)`, one per published version, inserted by a Flyway migration. It is not a runtime file, which nothing could reference, and not a seeded workspace, which is mutable by design. A published version is **never edited** (Flyway's checksum enforces it); a change is a new migration inserting `version + 1`.
- **v1 ships built-in templates only** (`kanban`, `scrum`). No template authoring; user-defined templates are a later decision (see *Reopen if*).
- **Instance = reference + delta**, not a materialised copy: `workspace.template_id`, `workspace.template_version`, `workspace.config_delta jsonb`. The cost being paid is a resolver, an upgrade procedure and a projection reconciler. The prototype's `Resolver.java` holds the whole rule set in about 450 lines of pure code, so the materialised-copy fallback the brief allows is not needed.
- **Pinned, not floating.** A workspace resolves against the version it is pinned to. A floating reference ("always latest") would let a Vectis release change live boards unreviewed, with nowhere to refuse a change that orphans items (cases C8, C13, T4). Pinning turns every template change into the same kind of reviewable diff as an instance edit. "Evolution is inheritable" holds: on upgrade the instance gets everything it did not override, without restating any of it.

### 2 · Resolution — merge on read, project on write

`effective = normalise(mergePatch(template[id, version], delta))`, then validate.

- **On read:** resolved in the application. Templates are immutable, so all rows (a few KB) are loaded once and cached forever by `(id, version)`. Resolution is a pure function over two small documents, so there is no template query per read and no stored effective document to go stale.
- **On write:** the one derived artefact that *is* stored is `board_column`, because `item.column_id` is a foreign key into it. Every delta write and every upgrade reconciles `board_column` by key, in the same transaction (see the [worked example](#worked-example)). A rename is an `UPDATE` of the same row, so no item moves.

Merge rules:

| Element | Shape | Rule |
|---|---|---|
| `states`, `itemTypes`, `fields` | object keyed by stable key | **merge by key**. A delta member merges field by field into the template member. `null` is a **tombstone** (element removed). A key the template lacks is an **addition** and must be complete. |
| scalar fields (`name`, `category`, `wipLimit`, `scale`, …) | scalar | delta value replaces; `null` unsets |
| list-valued fields (`enterFrom`) | array of keys | **replaced whole**. Arrays are values in RFC 7396, and a policy list is one decision. |
| `states.<k>.onBoard` | boolean, default `true` | whether the board renders the state's column. The `board_column` row exists either way. |
| `states.<k>.after` | state key | **placement anchor**: state `k` sits immediately after the named state, wherever that state ends up. The sparse way to place a state. |
| `stateOrder` | array of state keys | the wholesale way to reorder: replaced whole when overridden. May not list a state that has `after`. |
| `settings` | object of scalars | merge by key |
| `template`, `version`, `name` | — | not overridable; a delta setting them is rejected |

The order is normalised in three steps (`Resolver.normaliseOrder`): (1) take the instance's `stateOrder` if overridden, else the template's, minus keys that no longer exist and minus anchored states; (2) place a template state missing from it right after its nearest template-order predecessor that is present, and an unanchored instance-added state last; (3) place each anchored state immediately after its anchor. Two states anchored to the same state, an anchor that does not exist and an anchor cycle are rejected, so the result is deterministic.

Keys match `[a-z][a-zA-Z0-9-]{0,31}` and are immutable. Separating key from name is what makes "does a rename survive evolution" answerable: a rename is a field override, not a new element. `jsonb` does not keep member order, so order lives only in `stateOrder` and `after`.

Validation of the effective document (`Resolver.validate`): every state has a name and a category in `NOT_STARTED | IN_PROGRESS | DONE | DISCONTINUED`, with at least one `NOT_STARTED` and one `DONE` (a `DISCONTINUED` state is optional and never stands in for `DONE`); `wipLimit` is a positive integer; `onBoard` is a boolean; every `enterFrom` entry and every `after` names an existing state; at least one item type exists; `defaultItemType` names an existing type; field types and scales come from closed sets.

#### Categories and the `DISCONTINUED` end state

A state's category says what reaching it means, independently of its name:

| Category | Meaning | Terminal |
|---|---|---|
| `NOT_STARTED` | work not begun (Backlog, Refined, To Do) | no |
| `IN_PROGRESS` | work under way | no |
| `DONE` | work completed and delivered | yes |
| `DISCONTINUED` | work stopped without completion and with no viable path forward (a *no go*) | yes |

`DISCONTINUED` is its own end state rather than `DONE` plus a resolution flag, because it is neither finished nor not started: counting it as done would inflate delivery, and counting it as not started would read as open work. Both built-in templates therefore end in `"no-go": {"name": "No Go", "category": "DISCONTINUED", "onBoard": false}`, last in `stateOrder`.

**It is a state with a `board_column` row, kept off the board by default.** The row must exist, because `item.column_id` is a non-null foreign key and a discontinued item keeps its history rather than being deleted. It is off the board because a discontinued item is not in any working lane: a permanent column would fill with closed work that the team has decided not to pursue. The board renders only states with `onBoard: true`; discontinued items are reached through list and filter views. A team that wants the column shown sets `"no-go": {"onBoard": true}` in its delta, which projects to one flag update (D4). A workspace may also rename it (D3) or remove it while it is empty. The generic evolution rules apply to it unchanged (D5, D6).

### 3 · Evolution — case by case

An **upgrade** re-pins a workspace from version *n* to *m* and rewrites the delta only where the template change would otherwise change what the delta means:

- an **override** of an element the new version removed is **promoted**: it becomes a complete, instance-owned element (the old template values with the override applied) and is **kept in place** by anchoring it `after` its nearest surviving predecessor;
- a **tombstone** for an element the new version also removed is **dropped** as redundant;
- an **instance-added key the new version now also defines** is **adopted**: the delta entry is kept as an override, so the instance's values (including its placement) win and the template fills only the fields the instance never set;
- a state **anchored after a state the new version removed** is re-anchored after its nearest surviving predecessor, so it stays where it was; if nothing before it survives, `stateOrder` is pinned so it stays first;
- removed states are dropped from an overridden `stateOrder`, and a template reorder that an instance's own `stateOrder` masks is reported as a note.

The result must then resolve cleanly and must not remove any state or item type that items still use. Otherwise the upgrade is **refused** with the reasons. Moving items as part of an upgrade (a remap) is **not supported in v1**: the user moves them, then retries.

Every row below is an executable assertion in the prototype (ID in the last column). "Overridden" means the instance's delta touches that element.

| Template change | Instance has **not** overridden it | Instance **has** overridden it | Cases |
|---|---|---|---|
| **State (column) added** | Inherited, at the template's position. If the instance reordered, it is placed after its template predecessor. If the instance anchored a state after the same predecessor, the anchored state stays next to its anchor and the new one follows it. | *Instance had added the same key:* adopted. Instance values and placement win, the template fills the gaps, and the upgrade reports it. No item moves. | C1, C2, R2, R4 / C3, R5 |
| **State renamed** | Inherits the new name. | *Renamed it:* keeps its own name. *Deleted it:* stays deleted. | C4 / C5, C6 |
| **State removed** | Empty: removed (`DELETE` from `board_column`). Holding items: **upgrade refused** until they are moved. An instance state anchored after it is re-anchored in place. | *Customised it:* promoted to the instance's own state and kept in place, items untouched. *Deleted it:* tombstone dropped. | C7, C8, R6, R7 / C9, C10 |
| **States reordered** | Inherits the new order; states the instance placed with `after` move with their anchors. | *Reordered them itself (`stateOrder`):* its own order is kept and the upgrade says so. | C11, R3 / C12 |
| **The `no-go` end state added, removed or renamed** | Added: inherited off the board, after Done. Removed: refused while it holds items, otherwise deleted. | *Renamed it:* keeps its name, and keeps it (promoted, still off the board) if the template drops it. | D5, D6 / D3, D6 |
| **State removed that an instance policy references** | — | *`enterFrom` still names it:* **upgrade refused** (dangling reference). | C13 |
| **Item type added** | Inherited. | *Had added the same key:* adopted, the instance's name wins. | T1 / T2 |
| **Item type removed** | Unused: removed. Used by items: **upgrade refused**. | *Renamed it:* promoted, kept. *Deleted it:* tombstone dropped. | T3, T4 / T5, T6 |
| **Item type renamed** | Inherits (same rule as states). | *Deleted it:* stays deleted. | T7 |

Instance-side edits (a delta write) follow the same rules and are asserted as cases I1–I11 in the [prototype output](#prototype). A rename is an `UPDATE` of the same row. Removing a state or item type that items still use is rejected, as is any edit that leaves a dangling reference (`enterFrom`, `after`, `defaultItemType`, `stateOrder`).

**Not supported in v1:** changing an element's *key* (on either side; key = identity, so a template that needs it writes a removal plus an addition, and the rules above apply); remapping items during an upgrade; automatic upgrades; per-board state subsets; user-authored templates.

### 4 · Reviewability and API shape

The delta **is** the reviewable artefact: it is sparse (only what the instance changed), it has the same shape as the template, and it reads without replay. The API returns **all three**: the reference, the delta, and the effective configuration. It also returns an `overrides` list derived from the delta (`Resolver.overrides`), so a client renders the diff without reimplementing the merge:

```
GET /api/v1/workspaces/{key}/configuration          ETag: "7"
{
  "template":  {"id": "scrum", "version": 1, "latestVersion": 1},
  "revision":  7,
  "delta":     { …instance-refined.json… },
  "overrides": [
    {"op": "add", "path": "states.refined", "instance": {"name": "Refined", "category": "NOT_STARTED", "after": "backlog"}}
  ],
  "effective": { …the resolved document below… }
}

PUT  /api/v1/workspaces/{key}/configuration/delta    If-Match: "7"   body: the whole delta
     → 200 (same view, new revision) | 412 stale revision | 422 violations (path-keyed)
POST /api/v1/workspaces/{key}/configuration/upgrade  {"toVersion": 2, "dryRun": true}
     → {delta, effective, notes[], conflicts[], projection[]}; dryRun=false applies it if conflicts is empty
```

The delta is replaced with a PUT of the whole document, not a merge-patch PATCH. Applying a merge patch *to a delta* would give `null` two meanings (drop my override, or tombstone the element). With a whole-document PUT, `null` means only tombstone. Violations use the existing validation error shape, with the resolver's dotted path (`states.qa.category`) as the field. The delta is size-limited at the boundary (64 KiB).

Write path, one transaction: lock the workspace row (`select … for update`); check `config_revision`; resolve and validate; count occupancy (items per column key and per `fields->>'type'`); reconcile `board_column`; store the delta; bump the revision. The FK `item.column_id → board_column.id` (no cascade) is the backstop: deleting an occupied column fails even if a concurrent insert beat the count.

### 5 · Schema landing — `jsonb` on `workspace`, template in its own table

Sketch against `V1`/`V2` (the version number is whatever is next when it lands):

```sql
-- V3__workspace_configuration.sql (sketch)
create table workspace_template (
    id         text        not null,              -- 'kanban', 'scrum'
    version    int         not null check (version >= 1),
    document   jsonb       not null,
    created_at timestamptz not null default now(),
    primary key (id, version)
);

insert into workspace_template (id, version, document) values
    ('kanban', 1, '<kanban.v1.json, verbatim>'::jsonb),
    ('scrum',  1, '<scrum.v1.json, verbatim>'::jsonb);

alter table workspace
    add column template_id      text   not null,
    add column template_version int    not null,
    add column config_delta     jsonb  not null default '{}'::jsonb,
    add column config_revision  bigint not null default 0,
    add constraint workspace_template_fk
        foreign key (template_id, template_version) references workspace_template (id, version),
    add constraint workspace_config_delta_object check (jsonb_typeof(config_delta) = 'object');

alter table board_column
    add column key text not null,                 -- the state key the row projects
    add column on_board boolean not null default true,  -- false for off-board states such as no-go
    add constraint board_column_board_key_key unique (board_id, key),
    -- a reorder or an inserted column shifts ordinals; an immediate unique check fails on the first UPDATE
    drop constraint board_column_board_id_ordinal_key,
    add constraint board_column_board_id_ordinal_key unique (board_id, ordinal) deferrable initially deferred;
```

The `not null` columns without defaults fail on a non-empty table, deliberately: there is no deployed data to keep. A database that must be kept is backfilled to `('scrum', 1)`, with `board_column.key` derived from the Scrum names, first.

**Why `jsonb` on `workspace` and not override tables.** The delta means "a sparse patch relative to a pinned version". As tables that needs an override table per section, a nullable column per overridable field, a second flag per field to tell "inherit" from "unset", and a tombstone flag. Every policy VEC-15 adds would be a migration instead of a validated field. The integrity that matters is already relational (items → `board_column`); item types are referenced from `item.fields` (jsonb) anyway, so tables would add no foreign key there. One row also gives one optimistic lock, and it follows the `item.fields` precedent. The **template** gets its own table because it is shared, immutable, versioned and referenced by a foreign key.

### 6 · One model with the workflow engine

**States, transitions and transition policies are one model with VEC-15: they are sections of this same workspace configuration document (template-provided, delta-overridable, resolved by this resolver), and VEC-15 owns their enforcement and the policy vocabulary, not a second store.**

Consequences for VEC-15's scope:

- It reads `effective.states[k]` (`enterFrom`, `wipLimit`) through the configuration service; its "project's JSONB configuration payload" is this document. A new policy is a new validated document field, never a table.
- It decides transition semantics. `enterFrom` (the allowed source states of a state; absent means any) and `wipLimit` are the v1 vocabulary proposed here. It also decides whether guards apply to moves only or to create/import placement too; **moves only** is recommended, see the backlog import below.
- Transitions into a terminal state (`DONE` or `DISCONTINUED`) follow the same `enterFrom` rule as any other; the templates set none on `no-go`, so work can be discontinued from any state. Because `no-go` is off the board, discontinuing is an explicit action on an item, not a drop onto a column. Whether a terminal item may be reopened, and into which states, is VEC-15's decision.
- A v1 state *is* a board column (1:1), shown or not according to `onBoard`. A later status ≠ column mapping would add a `columns` section that references state keys, which is additive.
- Likewise, VEC-28's point scale is `effective.fields.storyPoints.scale`, and it counts only `DONE` as delivered.

## Worked example

The bytes below are the checked-in files the prototype loads; it asserts that each one is in canonical form (check S1).

[`kanban.v1.json`](./template-instance-model/kanban.v1.json):

```json
{
  "template": "kanban",
  "version": 1,
  "name": "Kanban",
  "states": {
    "todo": {"name": "To Do", "category": "NOT_STARTED"},
    "in-progress": {"name": "In Progress", "category": "IN_PROGRESS", "wipLimit": 5},
    "done": {"name": "Done", "category": "DONE"},
    "no-go": {"name": "No Go", "category": "DISCONTINUED", "onBoard": false}
  },
  "stateOrder": ["todo", "in-progress", "done", "no-go"],
  "itemTypes": {
    "task": {"name": "Task"},
    "bug": {"name": "Bug"},
    "epic": {"name": "Epic"}
  },
  "fields": {},
  "settings": {"defaultItemType": "task"}
}
```

[`scrum.v1.json`](./template-instance-model/scrum.v1.json):

```json
{
  "template": "scrum",
  "version": 1,
  "name": "Scrum",
  "states": {
    "backlog": {"name": "Backlog", "category": "NOT_STARTED"},
    "todo": {"name": "To Do", "category": "NOT_STARTED"},
    "in-progress": {"name": "In Progress", "category": "IN_PROGRESS"},
    "in-review": {"name": "In Review", "category": "IN_PROGRESS"},
    "done": {"name": "Done", "category": "DONE"},
    "no-go": {"name": "No Go", "category": "DISCONTINUED", "onBoard": false}
  },
  "stateOrder": ["backlog", "todo", "in-progress", "in-review", "done", "no-go"],
  "itemTypes": {
    "story": {"name": "Story"},
    "task": {"name": "Task"},
    "bug": {"name": "Bug"},
    "epic": {"name": "Epic"}
  },
  "fields": {
    "storyPoints": {"name": "Story points", "type": "number", "scale": "fibonacci"}
  },
  "settings": {"defaultItemType": "story"}
}
```

Names, order and item types are the VEC-10 draft's, plus the off-board `no-go` end state.

### The worked instance: this project's own backlog, with a Refined column

In this project's backlog, `REFINED` means *ready*: the item has passed refinement and is normally picked up the next sprint. Left too long, other changes can supersede it and it must be refined again, which is rare while the ready queue is short. When the backlog is imported, REFINED items get their own **Refined** column between *Backlog* and *To Do*; `PLANNED` items go to *To Do*, and `NO GO` items to the template's off-board `no-go` state.

The workspace is Scrum v1 plus [`instance-refined.json`](./template-instance-model/instance-refined.json), stored in `workspace.config_delta`:

```json
{
  "states": {
    "refined": {"name": "Refined", "category": "NOT_STARTED", "after": "backlog"}
  }
}
```

How it is represented, and why:

- **An instance-added state, not a template change.** A ready queue is this team's definition-of-ready gate, not part of Scrum as the template defines it; putting it in the template would add it to every Scrum workspace. As a delta it is one reviewable line. If a later Scrum version adds such a state under the same key, it is adopted with this instance's name and placement kept (R5).
- **A column, not a flag on Backlog or To Do items.** A column makes the ready queue visible and its length countable, which is what "the ready queue is short" is about.
- **Category `NOT_STARTED`**: refined work has not started, so reports and the import's category fallback treat it like Backlog and To Do.
- **Placed with `after`, not by restating `stateOrder`.** Overriding `stateOrder` to insert one column would copy the template's whole order into the delta and freeze it: the instance would stop inheriting template reorders only because it added a column. An anchor keeps the delta to one element and keeps Refined next to Backlog whatever the template later does (R2–R4, R6).
- **Going stale is a process rule, not a column property.** If it is ever enforced, it is a policy on this state in VEC-15's vocabulary (a maximum age, say), which is additive.

The prototype's output for it: the effective configuration, the overrides a reviewer sees, and the `board_column` statements that turn a freshly provisioned Scrum board into this instance (the import provisions with the delta directly, so on a new workspace the rows are simply inserted in this order):

```
== effective configuration: scrum v1 + instance-refined.json
{
  "template": "scrum",
  "version": 1,
  "name": "Scrum",
  "states": {
    "backlog": {"name": "Backlog", "category": "NOT_STARTED"},
    "refined": {"name": "Refined", "category": "NOT_STARTED", "after": "backlog"},
    "todo": {"name": "To Do", "category": "NOT_STARTED"},
    "in-progress": {"name": "In Progress", "category": "IN_PROGRESS"},
    "in-review": {"name": "In Review", "category": "IN_PROGRESS"},
    "done": {"name": "Done", "category": "DONE"},
    "no-go": {"name": "No Go", "category": "DISCONTINUED", "onBoard": false}
  },
  "stateOrder": ["backlog", "refined", "todo", "in-progress", "in-review", "done", "no-go"],
  "itemTypes": {
    "story": {"name": "Story"},
    "task": {"name": "Task"},
    "bug": {"name": "Bug"},
    "epic": {"name": "Epic"}
  },
  "fields": {
    "storyPoints": {"name": "Story points", "type": "number", "scale": "fibonacci"}
  },
  "settings": {"defaultItemType": "story"}
}
== overrides (the reviewable diff surface)
  add     states.refined = {"name": "Refined", "category": "NOT_STARTED", "after": "backlog"}
== board_column projection: freshly provisioned scrum -> instance
  INSERT refined name="Refined" ordinal=1
  UPDATE todo ordinal 1 -> 2
  UPDATE in-progress ordinal 2 -> 3
  UPDATE in-review ordinal 3 -> 4
  UPDATE done ordinal 4 -> 5
  UPDATE no-go ordinal 5 -> 6
```

Evolution, with the cases that prove it: if Scrum v2 adds a *Blocked* column, the instance inherits it and its delta is not rewritten (R2). If v2 reorders its columns, the instance inherits the new order and Refined moves with Backlog (R3). If v2 inserts a column right after Backlog, Refined stays next to Backlog and the new column follows (R4). If v2 dropped Backlog, the upgrade is refused while Backlog holds items; once it is empty, Refined stays first (R6).

### A second instance: rename, added column with a policy, removed item type

[`instance-review.json`](./template-instance-model/instance-review.json) is a team that renames *In Review*, adds a *QA* column with a WIP limit after it, makes *QA* the only way into *Done*, and removes the *Bug* type (defects live in a service desk):

```json
{
  "states": {
    "in-review": {"name": "Code Review"},
    "qa": {
      "name": "QA",
      "category": "IN_PROGRESS",
      "wipLimit": 3,
      "after": "in-review"
    },
    "done": {"enterFrom": ["qa"]}
  },
  "itemTypes": {"bug": null}
}
```

```
== overrides: scrum v1 + instance-review.json
  change  states.in-review.name: "In Review" -> "Code Review"
  add     states.qa = {"name": "QA", "category": "IN_PROGRESS", "wipLimit": 3, "after": "in-review"}
  add     states.done.enterFrom = ["qa"]
  remove  itemTypes.bug   (template: {"name": "Bug"})
== board_column projection: freshly provisioned scrum -> instance-review
  UPDATE in-review name "In Review" -> "Code Review"
  INSERT qa name="QA" ordinal=4
  UPDATE done ordinal 4 -> 5
  UPDATE no-go ordinal 5 -> 6
```

If a Scrum v2 dropped *In Review*, upgrading this instance would rewrite exactly one delta entry, to `"in-review": {"name": "Code Review", "category": "IN_PROGRESS", "after": "in-progress"}`: it gains the category the template used to supply and an anchor that keeps it in place. The effective configuration and the board would not change, even with four items in that column (C9).

## Prototype

Three dependency-free source files in [`template-instance-model/`](./template-instance-model/): `Json.java` (reader/writer, RFC 7396), `Resolver.java` (the model) and `TemplateModelSpike.java` (the cases). Throwaway and outside the Maven reactor; production would use Jackson. From the repository root, JDK 22+:

```bash
java docs/spikes/template-instance-model/TemplateModelSpike.java
```

Result on JDK 25.0.3, after the worked-example output above (non-zero exit on any failure):

```
-- sanity
PASS S1   kanban.v1.json is in canonical form
PASS S1   scrum.v1.json is in canonical form
PASS S1   instance-refined.json is in canonical form
PASS S1   instance-review.json is in canonical form
PASS S2   kanban v1 and scrum v1 resolve cleanly with an empty delta
PASS S3   the Refined instance resolves cleanly: Refined between Backlog and To Do, no stateOrder override
PASS S4   the review instance resolves cleanly, QA after the renamed review column, bug removed
-- instance edits
PASS I1   rename a state: UPDATE of the same board_column row, items do not move
PASS I2   add a state: unplaced goes last; placed with after lands right after its anchor
PASS I3   remove an empty state: allowed, projects to DELETE
PASS I4   remove an occupied state: rejected
PASS I5   reorder states: order replaced whole, projects to ordinal UPDATEs only
PASS I6   remove an item type still used by items: rejected
PASS I7   remove the default item type without re-pointing the default: rejected; re-pointed: allowed
PASS I8   delta outside its sections, tombstone of an unknown key, incomplete new state: rejected
PASS I9   remove a state another state's policy references: rejected
PASS I10  stateOrder listing an unknown or duplicate state: rejected
PASS I11  after naming a removed state, a cycle, two states after one anchor, or also listed in stateOrder: rejected
-- template evolution: the instance-added Refined column
PASS R1   worked instance: the delta is one element, the diff one line, the board one INSERT plus ordinal shifts
PASS R2   template adds a column elsewhere: inherited in place, Refined still after Backlog, delta untouched
PASS R3   template reorders columns: the new order is inherited and Refined moves with Backlog
PASS R4   template inserts a column right after Backlog: Refined stays next to its anchor, the new one follows
PASS R5   template adds the same key itself (named Ready, placed elsewhere): adopted, instance name and place win
PASS R6   template removes the anchor (Backlog): refused while it holds items; empty, Refined stays first
PASS R7   template removes a mid-board anchor: the added state is re-anchored to its surviving predecessor
PASS R8   every backlog status maps to a state key of the Refined instance; NO GO lands in DISCONTINUED, not DONE
-- the DISCONTINUED end state
PASS D1   both templates end in no-go: DISCONTINUED, off the board, still projected to a board_column row
PASS D2   DISCONTINUED is not DONE: dropping the only DONE state is rejected; onBoard must be a boolean
PASS D3   instance renames it: UPDATE of the same row, still off the board; a later template rename does not override it
PASS D4   instance puts it on the board: one flag UPDATE, no item moves
PASS D5   template gains it: inherited off the board, after Done, Refined untouched, delta not rewritten
PASS D6   template removes it: refused while it holds items; empty, removed; renamed by the instance, kept off the board
-- template evolution: states
PASS C1   template adds a state; instance untouched: inherited at the template's position
PASS C2   template adds a state; instance reordered: inherited, placed after its template predecessor
PASS C3   template adds a state the instance had already added under that key: adopted, instance values win
PASS C4   template renames a state; instance untouched: inherits the new name
PASS C5   template renames a state; instance renamed it too: instance name kept, other renames inherited
PASS C6   template renames a state; instance deleted it: stays deleted
PASS C7   template removes a state; instance untouched, state empty: removed, projects to DELETE
PASS C8   template removes a state; instance untouched, items in it: upgrade refused
PASS C9   template removes a state; instance renamed it: promoted to the instance's own, kept in place
PASS C10  template removes a state; instance deleted it: redundant tombstone dropped, delta now empty
PASS C11  template reorders states; instance untouched: inherits the new order
PASS C12  template reorders states; instance reordered too: instance order kept, and the upgrade says so
PASS C13  template removes a state an instance policy still references: upgrade refused
-- template evolution: item types
PASS T1   template adds an item type; instance untouched: inherited
PASS T2   template adds an item type the instance had already added: adopted, instance name wins
PASS T3   template removes an item type; instance untouched, unused: removed
PASS T4   template removes an item type; instance untouched, items use it: upgrade refused
PASS T5   template removes an item type; instance renamed it: promoted, kept
PASS T6   template removes an item type; instance deleted it: redundant tombstone dropped
PASS T7   template renames an item type; instance deleted it: stays deleted

52 passed, 0 failed
```

The assertions were checked for teeth by mutating a scratch copy of the resolver: ignoring `after` fails S3, S4, I2, I11, R1–R7 and C9; placing anchored states last fails S3, S4, I2, R1–R4, R6, R7 and C9; skipping re-anchoring fails R6 and R7; skipping promotion, or not keeping a promoted state in place, fails C9; dropping the reorder note fails C12; ignoring `onBoard` fails D1 and D3–D6; counting `DISCONTINUED` as `DONE`, or not validating `onBoard`, fails D2; dropping the `DISCONTINUED` category fails S2 and most evolution cases. The assertions are null-safe, so a broken resolver prints FAIL lines rather than aborting the run.

## What VEC-10 must change to conform

**Verdict: VEC-10 needs refinement, and it is a first refinement rather than a re-refinement.** It is still TO DO: it was refused at estimation until this spike decided the model. Its draft branch (`vec/VEC-10-template-provisioning`) materialises the board at provisioning; that part **stands**, but only as the projection of the resolved configuration. The copy stops being the configuration. Its acceptance criteria should be written against the changes below, and it should be split by extension, plausibly into (a) the migration, template rows and provisioning by reference, (b) column keys on the wire and the read-only configuration endpoint, and (c) the New Project form.

1. **Record the reference**: insert the workspace with `template_id`, `template_version` (latest) and a `config_delta`. Provisioning takes an optional initial delta, validated like any delta write (occupancy is empty): the form sends none, and the backlog import passes `instance-refined.json`. Drop the "nothing afterwards remembers which template it came from" contract.
2. **Load templates from `workspace_template`** (once, cached) instead of the `ProjectTemplate` enum lists; provisioning becomes `project(resolve(template, delta))`.
3. **Give columns keys**: `BoardColumn`, `BoardRepository` insert/select, `ColumnView`, `web/src/api/wire.ts`, each also carrying `onBoard`. Clients and importers address columns by key, and the board renders only `onBoard` columns.
4. **Make item types per workspace** (the effective `itemTypes`, no longer "catalogue only"). `TemplateView` is built from the document: states `{key, name, category, onBoard}`, item types `{key, name}`.
5. **Add read-only `GET /api/v1/workspaces/{key}/configuration`** (§4). Delta edits are a follow-on story; the upgrade endpoint waits for a template version 2.
6. **Ship the §5 migration** (or fold it into VEC-10's own).

Unchanged: `POST /api/v1/workspaces` taking a template id, one-transaction provisioning, the key-conflict mapping, and `EPIC_ISSUE_TYPE = "epic"` (now a key).

## Effect on other backlog items

- **VEC-15** (workflow engine): one model, not two (§6). Its scope narrows to enforcing `enterFrom` and `wipLimit` from the effective configuration and owning the policy vocabulary; it adds no store of its own.
- **VEC-54** (backlog import, and the import design it implements): the status table maps to state *keys*, `REFINED` goes to `refined`, `PLANNED` to `todo` and `NO GO` to the `DISCONTINUED` state `no-go` (no `resolution` field is needed), and the import provisions its workspace with `instance-refined.json` (details below).
- **VEC-28**: reads the point scale from `effective.fields.storyPoints.scale`. Velocity counts only items in a `DONE` state as delivered; an item in a `DISCONTINUED` state is never delivered, and burndown treats it as scope removed, not burned.
- **VEC-57** (vault reader): its `StatusCategory` gains the same `DISCONTINUED` value, so a source category and a state category mean the same thing.
- **VEC-11** (re-keying): unaffected. Configuration hangs off the workspace row, and state keys are not workspace keys.
- **No REFINED item is invalidated.** Two gain an additive note: **VEC-46**'s event contract should carry a configuration-changed event with the new `revision`, since a delta write or an upgrade changes every open board's columns; **VEC-14**'s RLS design must leave the global, built-in `workspace_template` rows readable to every tenant, while `config_delta` inherits the workspace row's scoping. VEC-22, VEC-23 and VEC-32 are unaffected.

## Compatibility with the backlog import design (VEC-53)

Compatible, with the Refined column expressed entirely as configuration:

- **Map vault status to a state *key*** and look up `board_column` by key, not name: `TO DO` → `backlog`, `REFINED` → `refined`, `PLANNED` → `todo`, `IN PROGRESS` → `in-progress`, `IN REVIEW` → `in-review`, `DONE` → `done`, `NO GO` → `no-go`. R8 checks every target key exists in the worked instance, and that NO GO lands in a `DISCONTINUED` state, not a `DONE` one.
- **Provision the import's workspace from `scrum` with `instance-refined.json` as the initial delta.** A plain Scrum workspace has no `refined` state (R8), so an import into one fails before writing rather than guessing, which is the import design's existing rule for a missing column.
- A column beyond the template is now a reviewable delta rather than template divergence, so Refined needs no template change. NO GO needs none either: `no-go` is part of both templates, and its category, not a `resolution` field, keeps it out of delivery reports.
- Its `StatusCategory`, with `DISCONTINUED` added, is this model's `category`, so its fallback becomes "first state in effective order with that category".
- Kind → type should validate against the effective `itemTypes`, not the Scrum catalogue.
- Guards enforced on every `column_id` write would refuse importing a DONE item into a workspace like the review instance, hence the moves-only recommendation in §6.

## Alternatives considered

- **Materialised copy** (the VEC-10 draft; the brief's permitted fallback): gives up inheritance and reviewability to avoid a cost the prototype shows is small.
- **Floating reference** (§1), **override tables** (§5), **template as a seeded workspace** (§1).
- **Placing an added state by overriding `stateOrder`**: works, but restates the template's whole order in the delta and stops the instance inheriting template reorders. Kept for deliberate wholesale reorders only.
- **Refined in the Scrum template**: would force a ready queue on every Scrum workspace (see the worked instance).
- **Delta as an op log or RFC 6902 JSON Patch**: index-addressed operations (`/stateOrder/3`) break when the template changes underneath, and an op log needs replay to be read. A merge patch is keyed and shaped like what it overrides.

## Consequences

- Every configuration write is one transaction: lock, count occupancy, reconcile `board_column`.
- Columns exist twice (configuration and `board_column`). Only the configuration service writes `board_column`; a test that `projection(effective, rows)` is empty catches drift.
- Upgrades are explicit and manual in v1. Bulk "upgrade every conflict-free workspace" is a later convenience.
- Element keys become part of the API and of item data (`fields.type`) and are immutable. `after` and `onBoard` are part of the delta vocabulary and appear in the effective document.
- Reports must treat the two terminal categories separately: `DONE` is delivery, `DISCONTINUED` is scope that left without delivery.
- Removing an item type races a concurrent item create, because `fields.type` has no foreign-key backstop. Item creates should take `for share` on the workspace row.

## Reopen if

- User-authored templates are required (template rows then need an owner, a tenant scope and RLS, R-SEC-2).
- Boards need per-board state subsets, or a status ≠ column mapping arrives.
- Reports need SQL-side access to the effective configuration at scale (e.g. a `category` filter in VEC-28 burndown queries). A stored `config_effective` or `board_column.category` written by the same transaction is additive.
