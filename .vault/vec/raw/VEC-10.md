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
