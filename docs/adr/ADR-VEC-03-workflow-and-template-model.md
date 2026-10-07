# ADR-VEC-03 — Workflow and template model: a pinned hierarchy of templates resolved to a workspace instance

| | |
|---|---|
| **Status** | Proposed (the PO decided its open questions on 2026-10-07; accepting the record is a separate decision) |
| **Date** | 2026-10-07 |
| **Deciders** | Product Owner |
| **Requirement** | [ADR-01](./ADR-01-product-requirements-and-features.md) **R-CORE-5** (template → instance), **R-CORE-3** (workflows are data); **R-SEC-2** as a constraint |
| **Evidence** | VEC-45 spike [`template-instance-model.md`](../spikes/template-instance-model.md) (single level, 58 cases) and VEC-66 spike [`template-hierarchy.md`](../spikes/template-hierarchy.md) (N levels, 35 further cases), one executable prototype: `java docs/spikes/template-instance-model/TemplateModelSpike.java` |

## Context

R-CORE-5 asks that a workspace be provisioned from a template and live on as a custom instance that references it, with inheritable template evolution and explicit, reviewable instance deltas. The PO then asked, on 2026-10-01, for a **mutable hierarchy** of templates: flexible enough to express agile, hybrid and non-agile methodologies (Scrum, Kanban, stage-gate, waterfall; waterfall maps to `phase` cadence with stages entered in sequence and optional gates, so it needs no concept of its own), with organisation and team variants, authored as versioned JSON (YAML accepted) documents with a published schema, and with the workflow runtime provided by the extracted state-machine library.

The forces: a change at a high level must reach many workspaces without anyone restating it; no change may silently orphan items or change what a state means for the items in it; every configuration must be reviewable as a diff; methodologies differ in item types, cadence and gates as well as columns; organisation and team levels are tenant data.

## Decision

1. **Documents.** A configuration is one JSON document with sections `states`, `stateOrder`, `itemTypes`, `fields`, `settings`, `cadence` and `locks`, plus identity (`template`, `version`, `name`, `description`). Elements have stable, immutable keys. Each element kind has a closed set of members, published as a JSON Schema. Canonical JSON is the stored and reviewed form; YAML is an authoring syntax compiled to it.
2. **Template versions are immutable and form a tree.** A root template version is a complete document. A derived version names the exact parent version it extends (`extends: {template, version}`) and carries an RFC 7396 merge-patch delta over the parent's resolved document. A **workspace** stores a pinned template version and a delta in the same vocabulary; it is the last level.
3. **Resolution** is VEC-45's two-document merge applied once per level, root first: merge by key, `null` as a tombstone, complete and placed additions, arrays replaced whole, order from `stateOrder` and level-local `after`/`before` anchors, then validation. Every published level must resolve to a valid workflow on its own. The nearest level wins, field by field. A resolved template version is materialised at publish; reading a workspace is one stored document plus its delta.
4. **States** carry one of three categories, `START_STATE`, `IN_PROGRESS`, `END_STATE`; an end state carries an outcome, `DELIVERED` or `DISCONTINUED`, and delivery is counted by outcome. A state may be a **gate** (leaving it needs a recorded decision with a number of approvals). `enterFrom` constrains the sources of a state. `board_column` rows are a projection of the states, reconciled by key.
5. **Methodology sections.** Item types declare `parents` (epic > story > task, project > deliverable > task), and dropping a parent type is refused while items sit under one. `cadence.mode` is `sprint`, `flow` or `phase` and decides the board mode. `locks` lists paths (a section, an element, or one member, nothing deeper) that no lower level may change, accumulating down the chain and enforced on both the delta and the resolved value, so a `stateOrder` lock fixes the set and order of states. `settings.gatedDelivery` makes "every way from a start state to a delivered end passes a gate an ancestor locked" an invariant of every resolved document, closing the ways round a gate that path locks cannot (a new end state, a changed outcome or category, a level's own or weak gate); a methodology using it also locks the `enterFrom` of its gates. The gates that count are frozen where the rule becomes binding (the level that locks it) and inherited unchanged, so no level below can add one (the strict variant, decided by the PO, Q5). Under the rule, creating or importing an item straight into a delivered end must pass a gate or be recorded as a gate decision (VEC-15, VEC-54). `parents` means "may sit under"; an optional, additive `requiresParent` flag may require a parent (Q4).
6. **Change is a new version, and propagation is an upgrade.** Every edge is pinned, and every propagation runs VEC-45's `upgrade` edge by edge. **Built-in edges** are rebased by the release build into new reviewed files, one per version; a built-in that does not rebase cleanly fails the build, and no built-in version exists only as a row. **Runtime edges** (tenant templates, workspaces) are walked by a durable job: a child whose owner chose `auto` and whose upgrade is conflict-free is upgraded in its own transaction, recomputed under its own lock from its current state (the plan is only a preview), and a re-pinned workspace emits `configuration.changed` with the ancestor as cause; every other child stays pinned, with the conflicts or a preview. Publishes are serialised per template key, children are discovered as each level is processed, and nothing is moved back to an older version. Occupancy is checked per workspace, so a change at any level is refused exactly for the workspaces whose items it would orphan or change in meaning. The default follow mode is `manual` (Q1): a child stays pinned until its owner opts in to `auto`.
7. **Authority and tenancy.** Built-in levels are owned by the system and change only through a Vectis release, as reviewed files. Organisation and team levels are tenant data: they extend built-ins or the same tenant's templates, never another tenant's; a tenant key may not shadow a built-in key. The follow mode of an edge is set by whoever may publish the child. The propagation job runs each child transaction in that child's tenant context. Only built-ins are roots, so every template derives from `base` (Q3), and a chain holds at most five template versions, root included, the workspace not counted (Q2).
8. **Runtime.** The workflow engine (VEC-15) builds its state machine from a generic subset compiled from the effective configuration: states with optional end outcome, initial states, and transitions `(from, event = target, to, guard?)`, a gate becoming the named guard `gate:<state>` on every transition leaving it. This subset, not VEC's whole document, is the format the definition adapter (VEC-76) covers.

## Consequences

- One resolver serves every level and the workspace. The costs paid: a materialised resolution per version (recomputed if the resolver's rules change), a propagation job with per-child transactions and a durable plan, and `board_column` reconciliation on every effective change.
- Every effective value has a provenance (which level set it), served by the configuration API, so a reviewer sees whose value it is as well as what it is.
- An organisation or Vectis change reaches a team only when the team follows automatically, or upgrades. Teams can fall behind; the plan and the configuration view show how far and why.
- A template publish is never refused for occupancy; its propagation is refused per workspace. Removing a state a lower level customised promotes it into that level instead of removing it.
- An upgrade that skips versions is computed from its two endpoints and can differ from the stepwise path (a state promoted and later re-added keeps the instance's place stepwise, the template's in one step); the preview shows which.
- Locks protect paths; flow-level governance needs an invariant. `gatedDelivery` is the one this decision ships; others would be added the same way, as checks on the resolved document.
- An organisation that runs several methodologies publishes its layer once per methodology (see the multi-parent alternative).
- VEC-46's document should say that ancestor-caused `configuration.changed` events are written by each workspace's own upgrade transaction, not by the publish transaction. This is a proposed amendment, filed as a follow-up; the event contract is unchanged.
- Keys are immutable at every level; re-parenting (switching methodology) and remapping items during an upgrade are not supported.
- YAML comments do not survive compilation; `description` is the annotation that does.
- Template rows gain an owner and become subject to row-level security; built-in rows stay readable by every tenant.

## Alternatives considered

- **Materialised copy at provisioning** (copy the template into the workspace, as the VEC-10 draft did; Taiga and OpenProject project templates): no inheritance, every improvement lost. Rejected in VEC-45 and still rejected.
- **Floating references** (a level always follows its parent's latest version, as Azure DevOps inherited processes and Jira shared schemes behave): changes land on live boards unreviewed, and there is nowhere to refuse one that orphans items. Rejected; `auto` follow keeps the convenience but only applies conflict-free upgrades, and only where the child's owner opted in.
- **A single level of inheritance** (Azure DevOps): forces organisation and team variants to be copies of each other. Rejected.
- **Multiple parents, mixins, or a policy overlay applied across methodologies** (an organisation layer that both its Scrum and its Kanban templates inherit). It answers the copy that remains: with one parent, an organisation with Scrum and Kanban teams publishes `acme-scrum` and `acme-kanban`, the criticism levelled at Azure DevOps moved along one axis. It loses for v1: with two parents the merge order of a diamond decides which value wins, so resolution is no longer a fold down one path; anchors and tombstones from two parents can contradict; a union of locks across parents can lock a path one parent's states need to change; and provenance and propagation stop being a tree walk. The cost accepted instead is one organisation layer per methodology in use, typically two or three small deltas. **Rejected for v1.** The reopen path is narrow: an overlay carrying only `locks` and invariants such as `gatedDelivery` (no states, so no ordering), applied after the chain to every workspace of a tenant, would give organisation-wide policy without diamonds.
- **Typed, fixed levels** (system → methodology → organisation → team): the same rules apply at every level, so the types would only forbid useful shapes (a hybrid derived from Scrum, a team without an organisation). Rejected; levels are roles, with a depth limit.
- **Flat chain merge without level-local anchors**: two levels anchoring next to the same state collide, so a methodology's placement would constrain every team below it. Rejected (case H3).
- **Locks as a separate permission scheme** (Jira's field configuration and permission schemes): a second store and a second resolution. Rejected in favour of a section of the same document, accumulated by the same chain.
- **Per-item-type workflows** (Jira workflow schemes): more expressive, but multiplies states, boards and projections. Deferred (see *Reopen if*).
- **One transaction per publish across all descendants**: one conflict fails all, and every workspace row is locked at once. Rejected for per-child transactions driven by a durable plan.
- **VEC's whole document as the state-machine adapter format**: would put boards, item types and cadence into a generic library. Rejected for the compiled subset.
- **Separate `DONE` and `DISCONTINUED` categories**, **override tables instead of `jsonb` deltas**, **JSON Patch or an op log as the delta**: rejected in VEC-45 for the reasons recorded there.

## Reopen if

- Teams need a different workflow per item type, per-board state subsets, or a status ≠ column mapping.
- A tenant needs a root that does not derive from the built-in base.
- Gate governance needs flow invariants beyond `gatedDelivery`.
- Organisations routinely maintain more than a few parallel organisation layers, one per methodology (the overlay of the multi-parent alternative).
- Reports need SQL-side access to effective configuration at scale (a stored per-workspace effective document, or category and outcome on `board_column`).
- Propagation fan-out (workspaces per publish) makes per-child transactions too slow for the publish preview to be useful.

## Decided by the PO (2026-10-07)

- **Q1** Follow mode: `manual` by default; a child stays pinned until its owner opts in to `auto`.
- **Q2** Depth: at most five template versions in a chain, root included, workspace not counted (`MAX_TEMPLATE_LEVELS = 5`), instead of the seven the spike proposed.
- **Q3** Roots: built-in only; every template derives from `base`. Reopen if a real tenant cannot fit `base`.
- **Q4** Item type `parents`: "may sit under", with an optional, additive `requiresParent` flag.
- **Q5** Gates: strict. Only gates locked at or above the level that made `gatedDelivery` binding count, and creating or importing an item straight into a delivered end must pass a gate or be recorded as a gate decision (VEC-15, VEC-54).
- **Q6** Annotations: `description` members are the surviving annotation; YAML comments are throwaway.
- **Q7** The definition adapter (VEC-76): parked until a second consumer needs it.
- **Q8** One organisation, several methodologies: the duplication is accepted for v1; reopen with a lock-and-invariant-only tenant overlay when a real organisation needs it.
