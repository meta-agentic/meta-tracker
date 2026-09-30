---
kind: story
space: vec
id: VEC-10
title: Project Template Provisioning & Base UI Board Configuration
status: TO DO
project: VEC
epic: VEC-2
storyPoints: 8
priority: P0
labels:
- Sprint-1
- VECTIS-002
- edition-community
- tier-Core
dependencies:
- VEC-9
relates:
- VEC-42
estimate:
  extension: 0.6
  intension: 0.8
  quadrant: complex + broad
  basis: up-front
  dated: '2026-08-30'
  note: 'Complex + broad -> REFUSED, not refined. Intension split out as VEC-45 (template->instance model
    spike; ADR-VEC-03 unwritten, R-CORE-5 inheritance semantics undecided). Extension is the first vertical
    slice through a stack with no middle: schema migration (no config column, no project table), repository,
    the first product REST resource in vectis-server (which would also silently set the API''s conventions),
    and the first HTTP client in the SPA. Stays TO DO. Re-point when VEC-45 closes; it should then be
    complicated and split by extension.'
---

## Description

As an Agile Coach, I want to initialize a project using an optimized template (Kanban or Scrum) through a web form, So that my engineering team immediately receives a tailored layout complete with standard columns and issue types.

**Acceptance Criteria**

* Submitting the 'New Project' form in the React frontend must successfully initialize the workspace.
* The workspace must render columns matching the template definition (e.g., Kanban vs. Scrum) dynamically.
* Invalid configurations passed to the backend API must be rejected with a clear validation notification in the UI.
* **(from VEC-42, 2026-09-28)** With the endpoints live, the Boards tab renders the boards, columns and items the server returns, and they are still there after a page reload — the end-to-end check VEC-42's merged client could not run without a backend.
* **(from VEC-42)** The contract checklist in `web/src/api/README.md` is reconciled against the endpoints as built: `startDate`, `dueDate` and `storyPoints` are recorded as unenforced conventions unless the API defines them.

**Developer Notes**
This is a full vertical slice. Build the JAX-RS endpoint to parse template models, storing configurations inside a PostgreSQL JSONB column on the Project table. In the frontend, map the workspace columns dynamically based on this JSON structure.

---

*Backlog ref: VECTIS-002 · Type: Story · Tier: Core · Planned: Sprint 1*

## Refinement outcome — NOT REFINED, split (2026-08-30)

Placed at **extension +0.6 / intension +0.8 — complex + broad**. The estimation
rule for that quadrant is binding: **do not commit unsplit.** Split the intension
out first; the remainder is then merely complicated and decomposes by extension.

**What is undefined (the intension).** R-CORE-5 in ADR-VEC-01 makes template →
instance a core concept — the instance *references* its template and carries only
its overrides, and template evolution is *inheritable*. None of that model is
decided: **ADR-VEC-03 is listed as a planned follow-on and has not been
written.** Writing acceptance criteria for this story today would mean inventing
the inheritance and override semantics in passing, inside a story whose visible
deliverable is a web form. → split out as **VEC-45**, a 4-day spike, now REFINED.

**What is broad (the extension).** This is the first vertical slice through a
stack that has no middle. Concretely, it needs:

* a schema migration — `V1__initial_schema.sql` has `workspace (id, key, name,
  created_at)` with no configuration column, no template table, and no `project`
  table at all (the developer note's "JSONB column on the Project table"
  describes something that does not exist);
* repository support in `vectis-persistence`;
* **the first REST resource in the product path** — `vectis-server` currently
  contains exactly one resource, `ExtensionDiagnosticsResource.java`, so this
  story would also be establishing the API's conventions: routing, error shape,
  validation-failure representation, and the reactive resource idiom;
* the "New Project" form and its validation surfacing in `@vectis/web`, which
  today has no HTTP client of any kind.

**Blocked on:** VEC-45 (template/instance model). **Also implicitly blocked on**
a decision about the REST layer's conventions, which no backlog item currently
owns — this story should not be the place that decision gets made silently.

**When VEC-45 closes,** re-point this item. It should land in the *complicated*
quadrant (high extension, low intension) and at that point the prescription
changes to **split by extension** — plausibly into a provisioning endpoint +
migration, and the client form — rather than estimating it up.

## 2026-09-25: reference implementation delivered PAST this item's own blocking gate

This item was placed **REFUSED, not refined** at estimation (2026-08-30): "Intension split out
as VEC-45 (template->instance model spike; ADR-VEC-03 unwritten, R-CORE-5 inheritance semantics
undecided)." VEC-45 is still REFINED, not done, and ADR-VEC-03 does not exist. An implementation branch built the
server slice anyway, under one stated assumption — provisioning COPIES the template's columns
into a fresh board with no link back to the template — chosen as the smallest model that does
not pre-empt R-CORE-5's reference-plus-overrides semantics. That assumption, and every REST
convention below, is a DRAFT set unilaterally on that branch, not a ratified decision. Shared-state and
distributed-model features go through an architect pass and a spike, not straight into a story, so this needs an architect pass against VEC-45/ADR-VEC-03 before it
can be called delivered.

**Branch:** `vec/VEC-10-template-provisioning`, head `5e63b0f`, 3 commits, cut from origin/main
`45814b1`. `mvn verify` green: 69/69 tests. No production deploy config exists for it yet — see
the new deployment-gap item below.

**What it built, provisionally:**
- `ProjectTemplate` enum in `vectis-domain` (Kanban, Scrum) with `instantiate(workspaceId)`.
- `WorkspaceProvisioningRepository`, one transaction for workspace + boards, a typed
  `WorkspaceKeyConflictException` on the unique-key violation.
- First product REST resources under `/api/v1`: `POST /workspaces`, `GET /workspaces/{key}`,
  `/{key}/boards`, `/{key}/items`, `GET /templates`. One error body shape across the API
  (`{error, message, violations[]}`), codes 400/409/404.
- No schema migration: nothing about the template is persisted.

**Silently set, needs ratification when VEC-45/ADR-VEC-03 land:**
- The whole `/api/v1` error-body shape and status-code convention — this is now precedent for
  every future endpoint, decided during implementation, not by design.
- Copy-not-reference provisioning semantics, which may conflict with whatever ADR-VEC-03 rules
  on R-CORE-5.
- Epics as a client-side `fields.type` convention the server never validates.
- `startDate`/`dueDate`/`storyPoints` as unvalidated pass-through fields, not a defined contract.

**Genuinely useful, independent of the above:** it confirms VEC-42's adapter needs NO code
change — every field name, path and shape it already assumed matches. VEC-42 can proceed to
review on that basis regardless of how this item resolves.

**Not done:** this item's own first acceptance criterion, the 'New Project' React form, was
deliberately left out — it depends on VEC-42's HTTP client, which is correctly not yet merged.

**Status stays TO DO.** It does not move to IN REVIEW: what's on the branch is an unratified
draft of a decision this item explicitly deferred, not this item's delivery. Next step: VEC-45
closes (or is time-boxed to a decision), ADR-VEC-03 gets written, and this branch is reviewed
AS PROPOSED INPUT to that decision — kept if the copy-semantics assumption is ratified, revised
or discarded if not.

## Local end-to-end run 2026-09-28

Branch rebased onto `main` locally (not pushed) and run against a throwaway Postgres:
Flyway applied V1+V2, `POST /api/v1/workspaces` provisioned a Scrum workspace (201, five
columns, UUIDv7 ids), and the merged VEC-42 client rendered its board and kept it after a
reload — the AC moved in from VEC-42 passes locally.

**Bug on this branch:** `GET /api/v1/templates` returns each template's issue types twice, as
`issueTypes` and as a misspelled `sueTypes` — the serializer reads the `issueTypes()`
accessor as an "is"-getter. Fix before this branch merges (annotate or rename the accessor).

