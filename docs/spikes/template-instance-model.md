# Template → instance model (spike) — draft ADR-03: workflow and template model

| | |
|---|---|
| **Status** | Proposed |
| **Type** | Spike (Core tier), VEC-45 — written to be renamed `docs/adr/ADR-03-workflow-and-template-model.md` |
| **Sprint** | VEC-S4 |
| **Requirement** | [ADR-01](../adr/ADR-01-product-requirements-and-features.md) **R-CORE-5** (template → instance), **R-CORE-3** (workflows are data) |
| **Deliverable** | This decision + a throwaway resolution prototype in [`template-instance-model/`](./template-instance-model/). No production code, no migration on `main`. |
| **Continued in** | VEC-66: [`template-hierarchy.md`](./template-hierarchy.md) (templates that extend templates) and the draft [ADR-VEC-03](../adr/ADR-VEC-03-workflow-and-template-model.md) |
| **Unblocks** | VEC-10 (provisioning). Informs VEC-15 (workflow engine), VEC-28 (point scale), VEC-11 (re-keying), VEC-54 (backlog import). |

## Context

R-CORE-5:

> A workspace is provisioned from a **template** (Kanban, Scrum) and lives on as a **custom instance**: the instance references its template and carries only its overrides (states, item types, fields, policies). Template evolution is inheritable; instance deltas are explicit and reviewable.

Nothing represents that today: `workspace` has no configuration and there is no template table. The VEC-10 draft branch provisions by **copying** columns into `board_column`, with no link back to the template, a choice made so as not to pre-empt this decision.

## Decision

A **template** is an immutable, versioned JSON document stored as a row. A **workspace** stores a pinned reference to one template version and a **delta**, an [RFC 7396](https://www.rfc-editor.org/rfc/rfc7396) JSON Merge Patch over the template, in `jsonb`. The **effective configuration** is the merge, resolved on read. Elements have stable **keys** and merge by key; a state the instance adds must say where it goes, normally with a sparse **`after`** or **`before`** anchor, so adding a column never freezes the template's order and never depends on member order, which `jsonb` does not keep. Template evolution arrives through an explicit, previewable **upgrade**. `board_column` rows become a **projection** of the effective states. Every state has one of three categories, **`START_STATE`**, **`IN_PROGRESS`** and **`END_STATE`**, and every end state declares an **outcome**, `DELIVERED` or `DISCONTINUED`. Both built-in templates end in `done` (delivered) and in a `no-go` end state (discontinued) that is kept off the board by default. Delivery is counted by outcome, never by category alone. The workflow engine (VEC-15) enforces **the same document**.

### 1 · Storage — template is a row; instance is reference + delta

- **Template = row** in `workspace_template (key, version, document jsonb)`, one per published version, inserted by a Flyway migration. It is not a runtime file, which nothing could reference, and not a seeded workspace, which is mutable by design. A published version is **never edited**, by convention: a change is a new migration inserting `version + 1`. Flyway's checksum only catches an edited migration file, not an `UPDATE` of the row; a `before update or delete` trigger that raises an error is the cheap database-side enforcement if one is wanted.
- **Keyed by text, not uuid.** Unlike entity tables, a template is identified by a stable, human-readable key (`kanban`, `scrum`) that appears in the API, in the document's own `template` member and in migrations; it is named `key`, as `workspace.key` is, not `id`.
- **v1 ships built-in templates only** (`kanban`, `scrum`). No template authoring; user-defined templates are a later decision (see *Reopen if*).
- **Instance = reference + delta**, not a materialised copy: `workspace.template_key`, `workspace.template_version`, `workspace.config_delta jsonb`. The cost being paid is a resolver, an upgrade procedure and a projection reconciler. The prototype's `Resolver.java` and `Validation.java` hold the whole rule set in under 600 lines of pure code, with the two diff views in `Diff.java`, so the materialised-copy fallback the brief allows is not needed.
- **Pinned, not floating.** A workspace resolves against the version it is pinned to. A floating reference ("always latest") would let a Vectis release change live boards unreviewed, with nowhere to refuse a change that orphans items (cases C8, C13, T4). Pinning turns every template change into the same kind of reviewable diff as an instance edit. "Evolution is inheritable" holds: on upgrade the instance gets everything it did not override, without restating any of it.

### 2 · Resolution — merge on read, project on write

`effective = normalise(mergePatch(template[key, version], delta))`, then validate.

- **On read:** resolved in the application. Templates are immutable, so all rows (a few KB) are loaded once and cached forever by `(key, version)`. Resolution is a pure function over two small documents, so there is no template query per read and no stored effective document to go stale.
- **On write:** the one derived artefact that *is* stored is `board_column`, because `item.column_id` is a foreign key into it. Every delta write and every upgrade reconciles `board_column` by key for **every board of the workspace**, in the same transaction (see the [worked example](#worked-example)); all of a workspace's boards project the same states. A rename is an `UPDATE` of the same row, so no item moves.

Merge rules:

| Element | Shape | Rule |
|---|---|---|
| a section (`states`, `itemTypes`, `fields`, `settings`) | object, never `null` | a section-level `null` or non-object is **rejected**: as a merge patch it would silently drop the whole template section while the overrides list showed nothing. `stateOrder`, when present, must be an array of keys. |
| `states`, `itemTypes`, `fields` | object keyed by stable key | **merge by key**. A delta member merges field by field into the template member. `null` is a **tombstone** (element removed). A key the template lacks is an **addition** and must be complete; a member that is neither an object nor `null` is rejected. |
| scalar fields (`name`, `category`, `outcome`, `wipLimit`, `scale`, …) | scalar | delta value replaces; `null` unsets |
| list-valued fields (`enterFrom`) | array of keys | **replaced whole**. Arrays are values in RFC 7396, and a policy list is one decision. |
| `states.<k>.onBoard` | boolean, default `true` | whether the board renders the state's column. The `board_column` row exists either way. |
| `states.<k>.after`, `states.<k>.before` | state key | **placement anchor**: state `k` sits immediately after (or before) the named state, wherever that state ends up. The sparse way to place a state; a state sets at most one. |
| `stateOrder` | array of state keys | the wholesale way to reorder: replaced whole when overridden. May not list an anchored state. |
| `settings` | object of scalars | merge by key |
| `template`, `version`, `name` | — | not overridable; a delta setting them is rejected |

**Every state the instance adds must be placed**, by `after`, by `before`, or by being listed in an overridden `stateOrder`; an unplaced addition is rejected (I2). The order is then normalised in three steps (`Resolver.normaliseOrder`): (1) take the instance's `stateOrder` if overridden, else the template's, minus keys that no longer exist and minus anchored states; (2) place a template state missing from it right after its nearest template-order predecessor that is present; (3) place each anchored state immediately after (or before) its anchor. Two states anchored to the same side of one state, an anchor that does not exist, a state with both anchors and an anchor cycle are rejected. The result is therefore a function of the document's content, never of its member order (I12).

Keys match `[a-z][a-zA-Z0-9-]{0,31}` and are immutable. Separating key from name is what makes "does a rename survive evolution" answerable: a rename is a field override, not a new element. `jsonb` does not keep member order, so order lives only in `stateOrder` and the anchors.

Validation of the effective document (`Validation.validate`): every state has a name and a category in `START_STATE | IN_PROGRESS | END_STATE`; an `END_STATE` state declares an outcome in `DELIVERED | DISCONTINUED`, and no other state has one; there is at least one `START_STATE` state and at least one `END_STATE` state with outcome `DELIVERED` (not exactly one: a team may keep *Done* and *Released* apart; a discontinued end state is optional and never stands in for a delivered one); `wipLimit` is a positive integer; `onBoard` is a boolean; every `enterFrom` entry and every anchor names an existing state; at least one state is on the board (a board with no columns is rejected); at least one item type exists; `defaultItemType` names an existing type; field types and scales come from closed sets.

#### Categories, end states and outcomes

A state's category says where in the flow it sits, independently of its name. There are exactly three:

| Category | Meaning | Outcome |
|---|---|---|
| `START_STATE` | work not begun (Backlog, Refined, To Do) | none |
| `IN_PROGRESS` | work under way | none |
| `END_STATE` | work has ended; nothing more is done on it | required |

What reaching an end state means is its **outcome**:

| Outcome | Meaning | Examples |
|---|---|---|
| `DELIVERED` | the work was completed and delivered | Done, Released |
| `DISCONTINUED` | work stopped without completion and with no viable path forward (a *no go*) | No Go, Won't Fix, Duplicate |

Done and NO GO are both end states, so they share a category, and the outcome carries the difference. A discontinued item is neither delivered nor open: counting it as delivered would inflate delivery, and counting it as not started would read as open work. Reports therefore count delivery by outcome `DELIVERED`, never by category alone. Because the outcome is a property of the state, a team can add its own end states, such as *Won't Fix* or *Duplicate*, each saying whether it delivered, without a new category (D9).

**`no-go` lives in both built-in templates**, as `"no-go": {"name": "No Go", "category": "END_STATE", "outcome": "DISCONTINUED", "onBoard": false}`, last in `stateOrder`, while `done` is `{"name": "Done", "category": "END_STATE", "outcome": "DELIVERED"}`. The argument that keeps Refined out of the Scrum template does not apply here. Refined is one team's definition-of-ready gate, and in the template it would put a visible column on every Scrum board. Discontinuing work is possible in every workspace, whatever its process, and an off-board state costs a workspace nothing it can see. Keeping it in the templates also means every workspace can discontinue an item from day one without editing its configuration, and the worked Refined delta stays one element (S3, R1).

**It is a state with a `board_column` row, kept off the board by default.** The row must exist, because `item.column_id` is a non-null foreign key and a discontinued item keeps its history rather than being deleted. It is off the board because a discontinued item is not in any working lane: a permanent column would fill with closed work that the team has decided not to pursue. The board renders only states with `onBoard: true`; discontinued items are reached through list and filter views. A team that wants the column shown sets `"no-go": {"onBoard": true}` in its delta, which projects to one flag update (D4). A workspace may also rename it (D3) or remove it while it is empty. The generic evolution rules apply to it unchanged (D5, D6), including adoption: a workspace that had added its own `no-go` keeps its name when a template version adds one; the upgrade's notes name the fields where the instance's value wins over a different template value and the fields the template fills in (such as `onBoard: false`, which hides a column the instance had shown by default), and the projection previews them before anything is applied (D7). If the workspace's own `no-go` has a different category or outcome, say an end state with outcome `DELIVERED`, the upgrade is **refused** rather than adopting it: inheriting `onBoard: false` would silently take a column that still counts as delivery off the board (D8).

### 3 · Evolution — case by case

An **upgrade** re-pins a workspace from version *n* to *m* and rewrites the delta only where the template change would otherwise change what the delta means:

- an **override** of an element the new version removed is **promoted**: it becomes a complete, instance-owned element (the old template values with the override applied) and is **kept in place** by anchoring it `after` its nearest surviving predecessor, or `before` its nearest surviving successor when nothing before it survives. A neighbour that the delta's anchors already place relative to the moved state is skipped, because anchoring to it would close a cycle and it moves with that state anyway (R9);
- a **tombstone** for an element the new version also removed is **dropped** as redundant;
- an **instance-added key the new version now also defines** is **adopted**: the delta entry is kept as an override, so the instance's values win, including its placement (every added state carries one), and the template fills only the fields the instance never set. The upgrade lists both the fields where the instance overrides a different template value and the fields the template fills. A **state whose category or outcome disagrees** with the template's is not adopted: the upgrade is refused until the instance aligns them or empties and removes the state, because together they are what reaching the state means (D8);
- a state **anchored to a state the new version removed** is re-anchored the same way, so it stays where it was and the delta stays sparse (R6, R7, R9). States are rewritten in key order, each seeing the anchors rewritten before it, so the rewritten delta never depends on member order. Only when every other surviving state is placed relative to the moved one does the upgrade fall back to pinning `stateOrder`;
- removed states are dropped from an overridden `stateOrder`, and a template reorder that an instance's own `stateOrder` masks is reported as a note.

The result must then resolve cleanly, must not remove any state or item type that items still use, and must not change the category or outcome of a state that holds items, unless the instance sets its own: those items would silently change meaning, for example start counting as delivered (C14). Otherwise the upgrade is **refused** with the reasons. A template change of a state's category or outcome is always listed in the notes. Moving items as part of an upgrade (a remap) is **not supported in v1**: the user moves them, then retries.

Every row below is an executable assertion in the prototype (ID in the last column). "Overridden" means the instance's delta touches that element.

| Template change | Instance has **not** overridden it | Instance **has** overridden it | Cases |
|---|---|---|---|
| **State (column) added** | Inherited, at the template's position. If the instance reordered, it is placed after its template predecessor. If the instance anchored a state after the same predecessor, the anchored state stays next to its anchor and the new one follows it. | *Instance had added the same key:* adopted. Instance values and placement win, the template fills the gaps, and the upgrade lists what the instance overrode and what the template filled. No item moves. A different category or outcome: **upgrade refused**. | C1, C2, R2, R4 / C3, R5, D7, D8 |
| **State renamed** | Inherits the new name. | *Renamed it:* keeps its own name. *Deleted it:* stays deleted. | C4 / C5, C6 |
| **State removed** | Empty: removed (`DELETE` from `board_column`). Holding items: **upgrade refused** until they are moved. An instance state anchored to it is re-anchored in place. | *Customised it:* promoted to the instance's own state and kept in place, items untouched. *Deleted it:* tombstone dropped. | C7, C8, R6, R7, R9 / C9, C10, R9 |
| **States reordered** | Inherits the new order; states the instance placed with an anchor move with their anchors. | *Reordered them itself (`stateOrder`):* its own order is kept and the upgrade says so. | C11, R3 / C12 |
| **State recategorised** (another category or outcome) | Empty: inherited, and the upgrade notes it. Holding items: **upgrade refused**, because the items would silently change meaning (say, start counting as delivered); the user moves them, or keeps the old category and outcome in the delta. | *Set its own category and outcome:* kept, and the upgrade notes the template's change. | C14 / C14 |
| **The `no-go` end state added, removed or renamed** | Added: inherited off the board, after Done. Removed: refused while it holds items, otherwise deleted. | *Renamed it:* keeps its name, and keeps it (promoted, still off the board) if the template drops it. *Had added its own:* adopted, its name wins, the filled-in `onBoard` is previewed; had added it with another category or outcome: **upgrade refused**. | D5, D6 / D3, D6, D7, D8 |
| **State removed that an instance policy references** | — | *`enterFrom` still names it:* **upgrade refused** (dangling reference). | C13 |
| **Item type added** | Inherited. | *Had added the same key:* adopted, the instance's name wins. | T1 / T2 |
| **Item type removed** | Unused: removed. Used by items: **upgrade refused**. | *Renamed it:* promoted, kept. *Deleted it:* tombstone dropped. | T3, T4 / T5, T6 |
| **Item type renamed** | Inherits (same rule as states). | *Deleted it:* stays deleted. | T7 |

Instance-side edits (a delta write) follow the same rules and are asserted as cases I1–I12 in the [prototype output](#prototype). A rename is an `UPDATE` of the same row. Removing a state or item type that items still use is rejected, as is any edit that leaves a dangling reference (`enterFrom`, an anchor, `defaultItemType`, `stateOrder`).

**Not supported in v1:** changing an element's *key* (on either side; key = identity, so a template that needs it writes a removal plus an addition, and the rules above apply); remapping items during an upgrade; automatic upgrades; per-board state subsets; user-authored templates.

### 4 · Reviewability and API shape

The delta **is** the reviewable artefact: it is sparse (only what the instance changed), it has the same shape as the template, and it reads without replay. The API returns **all three**: the reference, the delta, and the effective configuration. It also returns an `overrides` list derived from the delta (`Diff.overrides`), so a client renders the diff without reimplementing the merge:

```
GET /api/v1/workspaces/{key}/configuration          ETag: "7"
{
  "template":  {"key": "scrum", "version": 1, "latestVersion": 1},
  "revision":  7,
  "delta":     { …instance-refined.json… },
  "overrides": [
    {"op": "add", "path": "states.refined", "instance": {"name": "Refined", "category": "START_STATE", "after": "backlog"}}
  ],
  "effective": { …the resolved document below… }
}

PUT  /api/v1/workspaces/{key}/configuration/delta    If-Match: "7"   body: the whole delta
     → 200 (same view, new revision) | 412 stale revision | 422 violations (path-keyed)
POST /api/v1/workspaces/{key}/configuration/upgrade  {"toVersion": 2, "dryRun": true}
     → {delta, effective, notes[], conflicts[], projection[]}; dryRun=false applies it if conflicts is empty
```

The delta is replaced with a PUT of the whole document, not a merge-patch PATCH. Applying a merge patch *to a delta* would give `null` two meanings (drop my override, or tombstone the element). With a whole-document PUT, `null` means only tombstone. Validation runs in two layers, both answering 422 with the existing path-keyed validation error shape. At the **boundary**, before the resolver runs, the body is checked for shape: a JSON object of at most 64 KiB, only known sections, each section an object (`stateOrder` an array of strings), each element an object or `null`. The **resolver** then checks meaning, with its dotted path (`states.qa.category`) as the field. The resolver repeats the shape checks (I8), so a delta written by any other path, such as an import or an upgrade, cannot bypass them.

Write path, one transaction: lock the workspace row (`select … for update`); check `config_revision`; resolve and validate; count occupancy (items per column key and per `fields->>'type'`); reconcile `board_column` for every board of the workspace; store the delta; bump the revision. The FK `item.column_id → board_column.id` (no cascade) is the backstop: deleting an occupied column fails even if a concurrent insert beat the count.

### 5 · Schema landing — `jsonb` on `workspace`, template in its own table

Sketch against `V1`/`V2` (the version number is whatever is next when it lands):

```sql
-- V3__workspace_configuration.sql (sketch)
create table workspace_template (
    key        text        not null,              -- 'kanban', 'scrum': a stable public key, not a uuid
    version    int         not null check (version >= 1),
    document   jsonb       not null,
    created_at timestamptz not null default now(),
    primary key (key, version)
);

insert into workspace_template (key, version, document) values
    ('kanban', 1, '<kanban.v1.json, verbatim>'::jsonb),
    ('scrum',  1, '<scrum.v1.json, verbatim>'::jsonb);

alter table workspace
    add column template_key     text   not null,
    add column template_version int    not null,
    add column config_delta     jsonb  not null default '{}'::jsonb,
    add column config_revision  bigint not null default 0,
    add constraint workspace_template_fk
        foreign key (template_key, template_version) references workspace_template (key, version),
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
- `enterFrom` applies to entering an end state, whatever its outcome, exactly as to any other state. The templates set none on `no-go`, so work can be discontinued from any state; a team that wants, say, only refined or in-progress work to be discontinued sets `enterFrom` on `no-go` in its delta. Because `no-go` is off the board, discontinuing is an explicit action on an item, not a drop onto a column. Whether an item in an end state may be reopened, and into which states, is VEC-15's decision.
- A v1 state *is* a board column (1:1), shown or not according to `onBoard`. A later status ≠ column mapping would add a `columns` section that references state keys, which is additive.
- Likewise, VEC-28's point scale is `effective.fields.storyPoints.scale`, and it counts as delivered only an item in an end state with outcome `DELIVERED`: an item in an end state with outcome `DISCONTINUED` never counts toward velocity, throughput or burndown.

## Worked example

The bytes below are the checked-in files the prototype loads; it asserts that each one is in canonical form (check S1).

[`kanban.v1.json`](./template-instance-model/kanban.v1.json):

```json
{
  "template": "kanban",
  "version": 1,
  "name": "Kanban",
  "states": {
    "todo": {"name": "To Do", "category": "START_STATE"},
    "in-progress": {"name": "In Progress", "category": "IN_PROGRESS", "wipLimit": 5},
    "done": {"name": "Done", "category": "END_STATE", "outcome": "DELIVERED"},
    "no-go": {
      "name": "No Go",
      "category": "END_STATE",
      "outcome": "DISCONTINUED",
      "onBoard": false
    }
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
    "backlog": {"name": "Backlog", "category": "START_STATE"},
    "todo": {"name": "To Do", "category": "START_STATE"},
    "in-progress": {"name": "In Progress", "category": "IN_PROGRESS"},
    "in-review": {"name": "In Review", "category": "IN_PROGRESS"},
    "done": {"name": "Done", "category": "END_STATE", "outcome": "DELIVERED"},
    "no-go": {
      "name": "No Go",
      "category": "END_STATE",
      "outcome": "DISCONTINUED",
      "onBoard": false
    }
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

In this project's backlog, `REFINED` means *ready*: the item has not started, but it has been curated and is ready to be worked, not just planned. It is normally picked up the next sprint. Left too long, other changes can supersede it and it must be refined again, which is rare while the ready queue is short. When the backlog is imported, REFINED items get their own **Refined** column between *Backlog* and *To Do*; `PLANNED` items go to *To Do*, and `NO GO` items to the template's off-board `no-go` state.

The workspace is Scrum v1 plus [`instance-refined.json`](./template-instance-model/instance-refined.json), stored in `workspace.config_delta`:

```json
{
  "states": {
    "refined": {"name": "Refined", "category": "START_STATE", "after": "backlog"}
  }
}
```

How it is represented, and why:

- **An instance-added state, not a template change.** A ready queue is this team's definition-of-ready gate, not part of Scrum as the template defines it; putting it in the template would add it to every Scrum workspace. As a delta it is one reviewable line. If a later Scrum version adds such a state under the same key, it is adopted with this instance's name and placement kept (R5).
- **A column, not a flag on Backlog or To Do items.** A column makes the ready queue visible and its length countable, which is what "the ready queue is short" is about.
- **Category `START_STATE`**: refined work has not started, so reports and the import's category fallback treat it like Backlog and To Do.
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
    "backlog": {"name": "Backlog", "category": "START_STATE"},
    "refined": {"name": "Refined", "category": "START_STATE", "after": "backlog"},
    "todo": {"name": "To Do", "category": "START_STATE"},
    "in-progress": {"name": "In Progress", "category": "IN_PROGRESS"},
    "in-review": {"name": "In Review", "category": "IN_PROGRESS"},
    "done": {"name": "Done", "category": "END_STATE", "outcome": "DELIVERED"},
    "no-go": {
      "name": "No Go",
      "category": "END_STATE",
      "outcome": "DISCONTINUED",
      "onBoard": false
    }
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
  add     states.refined = {"name": "Refined", "category": "START_STATE", "after": "backlog"}
== board_column projection: freshly provisioned scrum -> instance
  INSERT refined name="Refined" ordinal=1
  UPDATE todo ordinal 1 -> 2
  UPDATE in-progress ordinal 2 -> 3
  UPDATE in-review ordinal 3 -> 4
  UPDATE done ordinal 4 -> 5
  UPDATE no-go ordinal 5 -> 6
```

Evolution, with the cases that prove it: if Scrum v2 adds a *Blocked* column, the instance inherits it and its delta is not rewritten (R2). If v2 reorders its columns, the instance inherits the new order and Refined moves with Backlog (R3). If v2 inserts a column right after Backlog, Refined stays next to Backlog and the new column follows (R4). If v2 dropped Backlog, the upgrade is refused while Backlog holds items; once it is empty, Refined stays first, re-anchored `before` To Do, and the delta is still one element (R6). Had the instance also renamed Backlog, v2 dropping it would promote Backlog to the instance's own state, placed `before` To Do rather than before Refined, which is anchored to it, and Refined would keep its `after: backlog` (R9).

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

Six dependency-free source files in [`template-instance-model/`](./template-instance-model/): `Json.java` (reader/writer, RFC 7396), `Resolver.java` (the model), `Validation.java` (validation of the effective document), `Diff.java` (the override list and the `board_column` projection), `TemplateModelSpike.java` (the cases) and `Cases.java` (their assertion helpers). Throwaway and outside the Maven reactor; production would use Jackson. From the repository root, JDK 22+:

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
PASS I2   add a state: unplaced is rejected (jsonb keeps no member order); after, before or stateOrder place it
PASS I3   remove an empty state: allowed, projects to DELETE
PASS I4   remove an occupied state: rejected
PASS I5   reorder states: order replaced whole, projects to ordinal UPDATEs only
PASS I6   remove an item type still used by items: rejected
PASS I7   remove the default item type without re-pointing the default: rejected; re-pointed: allowed
PASS I8   delta outside its sections, tombstone of an unknown key, incomplete new state, badly shaped section: rejected
PASS I9   remove a state another state's policy references: rejected
PASS I10  stateOrder listing an unknown or duplicate state: rejected
PASS I11  an anchor naming a removed state, a cycle, two states on one side of an anchor, both anchors, or also listed in stateOrder: rejected
PASS I12  resolution ignores member order (jsonb keeps none): each delta, members reversed, resolves to the same order and content
-- template evolution: the instance-added Refined column
PASS R1   worked instance: the delta is one element, the diff one line, the board one INSERT plus ordinal shifts
PASS R2   template adds a column elsewhere: inherited in place, Refined still after Backlog, delta untouched
PASS R3   template reorders columns: the new order is inherited and Refined moves with Backlog
PASS R4   template inserts a column right after Backlog: Refined stays next to its anchor, the new one follows
PASS R5   template adds the same key itself (named Ready, placed elsewhere): adopted, instance name and place win
PASS R6   template removes the anchor (Backlog): refused while it holds items; empty, Refined stays first, anchored before To Do
PASS R7   template removes a mid-board anchor: the added state is re-anchored to its surviving predecessor
PASS R8   every backlog status maps to a state key of the Refined instance, NO GO to an end state with outcome DISCONTINUED; an unknown status falls back by category and outcome
PASS R9   re-anchoring never anchors to a state placed relative to the moved one: no false cycle, same delta with members reversed
-- end states and outcomes
PASS D1   both templates end in done (DELIVERED) and no-go (DISCONTINUED, off the board, still projected to a board_column row)
PASS D2   a discontinued end state is no delivery: dropping the only DELIVERED one is rejected; onBoard must be a boolean, and some state on the board
PASS D3   instance renames it: UPDATE of the same row, still off the board; a later template rename does not override it
PASS D4   instance puts it on the board: one flag UPDATE, no item moves
PASS D5   template gains it: inherited off the board, after Done, Refined untouched, delta not rewritten
PASS D6   template removes it: refused while it holds items; empty, removed; renamed by the instance, kept off the board
PASS D7   instance had added its own no-go, then the template adds one: adopted, instance name wins; the template's onBoard=false is filled in and previewed
PASS D8   instance had added its own no-go as a DELIVERED end state, or not as an end state, then the template adds it as DISCONTINUED: upgrade refused
PASS D9   an END_STATE declares its outcome, DELIVERED or DISCONTINUED, and no other state has one; a team adds its own end states
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
PASS C14  template recategorises a state: refused while items are in it, noted when empty, kept when the instance sets its own
-- template evolution: item types
PASS T1   template adds an item type; instance untouched: inherited
PASS T2   template adds an item type the instance had already added: adopted, instance name wins
PASS T3   template removes an item type; instance untouched, unused: removed
PASS T4   template removes an item type; instance untouched, items use it: upgrade refused
PASS T5   template removes an item type; instance renamed it: promoted, kept
PASS T6   template removes an item type; instance deleted it: redundant tombstone dropped
PASS T7   template renames an item type; instance deleted it: stays deleted

58 passed, 0 failed
```

The assertions were checked for teeth by mutating a scratch copy of the prototype, and each mutant fails the cases named: ignoring `after` fails S3, S4, I2, I11, I12, R1–R9, C9, D5 and D9; treating `before` as `after` fails I2, I12, R6 and R9; accepting an unplaced added state fails I2; accepting a section-level `null` fails I8; skipping re-anchoring fails R6, R7 and R9, and dropping only the `before` fallback fails R6 and R9; re-anchoring to a neighbour that is placed relative to the moved state, rewriting states in member order rather than key order (either the promoted states or the re-anchored ones), or checking for cycles against the anchors as they were before the upgrade, fails R9; skipping promotion, or not keeping a promoted state in place, fails R9, C9 and D6; dropping the reorder note fails C12; dropping from an adoption note either the fields the template fills or the fields the instance overrides fails D7 and D8; adopting a state whose category or outcome disagrees, or comparing the category alone, fails D8; a fallback that ignores the outcome fails R8; skipping the recategorisation check, refusing a recategorisation of an empty state, ignoring an instance's own category and outcome, or not noting the change, fails C14, and describing a state by its category alone fails D8 and C14; ignoring `onBoard` fails D1 and D3–D7; not validating `onBoard`, or accepting a board with no on-board state, fails D2; counting any end state as delivered fails D2 and D9; accepting an end state without an outcome, or an outcome on a state that is not an end state, fails D9; dropping the `END_STATE` category fails S2 and most other cases. Two mutants survive and are equivalent: appending unplaced states in member order, and resolving anchors in member order, change nothing a valid delta can express, because unplaced additions are rejected and anchors on one side of a state are unique. The assertions are null-safe, so a broken resolver prints FAIL lines rather than aborting the run.

## What VEC-10 must change to conform

**Verdict: VEC-10 needs refinement, and it is a first refinement rather than a re-refinement.** It is still TO DO: it was refused at estimation until this spike decided the model. Its draft branch (`vec/VEC-10-template-provisioning`) materialises the board at provisioning; that part **stands**, but only as the projection of the resolved configuration. The copy stops being the configuration. Its acceptance criteria should be written against the changes below, and it should be split by extension, plausibly into (a) the migration, template rows and provisioning by reference, (b) column keys on the wire and the read-only configuration endpoint, and (c) the New Project form.

1. **Record the reference**: insert the workspace with `template_key`, `template_version` (latest) and a `config_delta`. Provisioning takes an optional initial delta, validated like any delta write (occupancy is empty): the form sends none, and the backlog import passes `instance-refined.json`. Drop the "nothing afterwards remembers which template it came from" contract.
2. **Load templates from `workspace_template`** (once, cached) instead of the `ProjectTemplate` enum lists; provisioning becomes `project(resolve(template, delta))`.
3. **Give columns keys**: `BoardColumn`, `BoardRepository` insert/select, `ColumnView`, `web/src/api/wire.ts`, each also carrying `onBoard`. Clients and importers address columns by key, and the board renders only `onBoard` columns.
4. **Make item types per workspace** (the effective `itemTypes`, no longer "catalogue only"). `TemplateView` is built from the document: states `{key, name, category, outcome, onBoard}`, item types `{key, name}`.
5. **Add read-only `GET /api/v1/workspaces/{key}/configuration`** (§4). Delta edits are a follow-on story; the upgrade endpoint waits for a template version 2.
6. **Ship the §5 migration** (or fold it into VEC-10's own).

Unchanged: `POST /api/v1/workspaces` taking a template key, one-transaction provisioning, the key-conflict mapping, and `EPIC_ISSUE_TYPE = "epic"` (now a key).

## Effect on other backlog items

- **VEC-15** (workflow engine): one model, not two (§6). Its scope narrows to enforcing `enterFrom` and `wipLimit` from the effective configuration and owning the policy vocabulary; it adds no store of its own.
- **VEC-54** (backlog import, and the import design it implements): the status table maps to state *keys*, `REFINED` goes to `refined`, `PLANNED` to `todo` and `NO GO` to `no-go`, an end state with outcome `DISCONTINUED` (no `resolution` field is needed), and the import provisions its workspace with `instance-refined.json` (details below).
- **VEC-28**: reads the point scale from `effective.fields.storyPoints.scale`. Delivery is counted by outcome, never by category alone: only an item in an end state with outcome `DELIVERED` is delivered. An item in an end state with outcome `DISCONTINUED` never counts toward velocity, throughput or the burned line of a burndown; its points leave the remaining scope as a scope change.
- **VEC-57** (the read-only `BacklogSource` contract): its `StatusCategory` has the same three values as the state categories, `START_STATE`, `IN_PROGRESS` and `END_STATE`, an `Outcome` of `DELIVERED` or `DISCONTINUED` is carried exactly when the category is `END_STATE`, and `REFINED` is a verbatim status reported as `START_STATE`, not a category of its own (see below).
- **VEC-11** (re-keying): unaffected. Configuration hangs off the workspace row, and state keys are not workspace keys.
- **No REFINED item is invalidated.** Two gain an additive note: **VEC-46**'s event contract should carry a configuration-changed event with the new `revision`, since a delta write or an upgrade changes every open board's columns; **VEC-14**'s RLS design must leave the global, built-in `workspace_template` rows readable to every tenant, while `config_delta` inherits the workspace row's scoping. VEC-22, VEC-23 and VEC-32 are unaffected.

## Compatibility with the backlog import

The import design ([VEC-53](../../.vault/vec/output/VEC-53.md)) and its implementation ([VEC-54](../../.vault/vec/raw/VEC-54.md)) are in this repository's backlog; the rules this model relies on are restated here in full so this record stands on its own. The import reads this repository's `.vault/` backlog through the read-only `BacklogSource` contract now on `main` (`vectis-extension-spi`), whose items carry a verbatim `status`, a portable `StatusCategory` (`START_STATE`, `IN_PROGRESS` or `END_STATE`) and, exactly when the category is `END_STATE`, an `Outcome` (`DELIVERED` or `DISCONTINUED`), and writes them into one workspace. For that, the model requires:

- **Status maps to a state *key***, and `board_column` is looked up by key, not name: `TO DO` → `backlog`, `REFINED` → `refined`, `PLANNED` → `todo`, `IN PROGRESS` → `in-progress`, `IN REVIEW` → `in-review`, `DONE` → `done`, `NO GO` → `no-go`. A rename in the workspace then never breaks the import. R8 checks every target key exists in the worked instance, and that NO GO lands in an end state with outcome `DISCONTINUED`, DONE in one with outcome `DELIVERED`.
- **The import's workspace is provisioned from `scrum` with `instance-refined.json` as its initial delta.** A plain Scrum workspace has no `refined` state (R8); an import into one fails before writing anything rather than guessing a column.
- **Source and state categories agree**: `StatusCategory` and the state categories are the same three values, `START_STATE`, `IN_PROGRESS` and `END_STATE`, and a source item in an end state carries the same `DELIVERED` or `DISCONTINUED` outcome as a state. `REFINED` and `PLANNED` are not categories but known verbatim statuses, both reported as `START_STATE` and mapped by key as above; `NO GO` is reported as `END_STATE` with outcome `DISCONTINUED` and lands in `no-go`. **An unrecognised status falls back** to the first state in effective order with the same category and, for an end state, the same outcome (`Resolver.fallback`, R8), with no translation between the two vocabularies. A workspace with no such state refuses the item.
- **No column beyond the template needs a template change**: Refined is a reviewable delta, and `no-go` is already in both templates. Its outcome, not a `resolution` field, keeps NO GO out of delivery reports.
- **Kind → type** is validated against the effective `itemTypes`, not a fixed Scrum catalogue.
- Guards enforced on every `column_id` write would refuse importing a DONE item into a workspace like the review instance, hence the moves-only recommendation in §6.

## Alternatives considered

- **Materialised copy** (the VEC-10 draft; the brief's permitted fallback): gives up inheritance and reviewability to avoid a cost the prototype shows is small.
- **Floating reference** (§1), **override tables** (§5), **template as a seeded workspace** (§1).
- **Placing an added state by overriding `stateOrder`**: works, but restates the template's whole order in the delta and stops the instance inheriting template reorders. Kept for deliberate wholesale reorders only.
- **Refined in the Scrum template**: would force a ready queue on every Scrum workspace (see the worked instance).
- **Separate `DONE` and `DISCONTINUED` categories** (an earlier draft of this record, with four categories): considered and dropped. Done and NO GO are both end states, so a category per outcome makes "is it finished?" a question about two categories and invites reports that count every terminal category as delivery. With one `END_STATE` category and an outcome, a team can add end states such as *Won't Fix* or *Duplicate* without a new category, and delivery is always counted by outcome.
- **Delta as an op log or RFC 6902 JSON Patch**: index-addressed operations (`/stateOrder/3`) break when the template changes underneath, and an op log needs replay to be read. A merge patch is keyed and shaped like what it overrides.

## Consequences

- Every configuration write is one transaction: lock, count occupancy, reconcile `board_column` for every board of the workspace.
- Columns exist twice (configuration and `board_column`). Only the configuration service writes `board_column`; a test that `projection(effective, rows)` is empty catches drift.
- Upgrades are explicit and manual in v1. Bulk "upgrade every conflict-free workspace" is a later convenience.
- Element keys become part of the API and of item data (`fields.type`) and are immutable. The anchors (`after`, `before`) and `onBoard` are part of the delta vocabulary and appear in the effective document.
- Reports count delivery by outcome `DELIVERED`, never by category alone: an end state with outcome `DISCONTINUED` is scope that left without delivery.
- Removing an item type races a concurrent item create, because `fields.type` has no foreign-key backstop. Item creates should take `for share` on the workspace row.

## Reopen if

- User-authored templates are required (template rows then need an owner, a tenant scope and RLS, R-SEC-2).
- Boards need per-board state subsets, or a status ≠ column mapping arrives.
- Reports need SQL-side access to the effective configuration at scale (e.g. an `outcome` filter in VEC-28 burndown queries). A stored `config_effective`, or `board_column.category` and `board_column.outcome`, written by the same transaction is additive.
