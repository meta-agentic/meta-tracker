---
kind: spike
space: vec
id: VEC-45
title: Template to instance model — inheritance and override semantics for workspace provisioning
status: REFINED
project: VEC
epic: VEC-2
priority: P1
labels:
- edition-community
- spike
- tier-Core
storyPoints: 5
estimate:
  extension: -0.3
  intension: 0.9
  quadrant: complex
  basis: up-front
  dated: '2026-08-30'
  note: 'Complex, narrow -> spike. This IS the intension split out of VEC-10 (complex+broad, refused unsplit).
    Low extension: one findings/draft-ADR document plus a throwaway resolution prototype; no product module
    changes. Very high intension: R-CORE-5 states template->instance as a requirement but ADR-VEC-03 is
    unwritten, the shipped schema has no config column and no template table, the template-evolution cases
    are interacting rather than additive, and whether states/policies share a model with VEC-15 is undecided.
    Time-boxed to 4 days.'
---

## Origin

Split out of **VEC-10** (Project Template Provisioning & Base UI Board
Configuration) during refinement. VEC-10 landed in the *complex + broad*
quadrant — high extension **and** high intension — and the estimation rule for
that quadrant is: do not commit unsplit; separate the undefined part first. This
spike is that undefined part. VEC-10 stays at `TO DO` until this closes.

## Type

**Spike — time-boxed to 4 working days.** The deliverable is a decided model
written up as a draft ADR, not a feature.

## The question this spike must answer

**How is R-CORE-5's template → instance relationship actually represented and
evaluated?**

ADR-VEC-01 §1 states it as a requirement and flags it as a core Vectis concept
rather than an implementation detail:

> A workspace is provisioned from a **template** (Kanban, Scrum) and lives on as
> a **custom instance**: the instance references its template and carries only
> its overrides (states, item types, fields, policies). Template evolution is
> inheritable; instance deltas are explicit and reviewable.

Every clause in that sentence is an open decision:

1. **Storage.** Is a template a row, a file, or a seeded workspace? Does the
   instance store a *reference* plus a delta document, or a materialized copy?
   The requirement says reference-plus-overrides; a materialized copy is far
   simpler and breaks "template evolution is inheritable". Which cost is being
   paid?
2. **Resolution.** How is the effective configuration computed — a merge of
   template and delta at read time, or resolved and cached on write? What is the
   merge rule for a list (board columns) as opposed to a scalar: replace whole,
   or merge by key?
3. **Evolution.** A template gains a column after ten instances exist. Do they
   inherit it? Does an instance that renamed that column inherit the rename?
   What happens to an instance that *deleted* it? These are the cases that decide
   whether the model is usable or a support burden.
4. **Reviewability.** "Instance deltas are explicit and reviewable" implies a
   diff surface. What is the delta's shape such that it can be rendered and
   understood — and what does the API return, the delta, the effective config, or
   both?
5. **Schema landing.** `V1__initial_schema.sql` has `workspace (id, key, name,
   created_at)` — no configuration column, no template table, no `project`
   table. VEC-10's own developer note says "a PostgreSQL JSONB column on the
   Project table", which does not exist. What migration does this model need, and
   does the config live on `workspace` as jsonb (matching the `item.fields`
   precedent) or in its own tables?
6. **Overlap with the workflow engine.** R-CORE-5's overrides include *states*
   and *policies*, which is also **VEC-15**'s territory (schema-flexible workflow
   engine and transition guardrails). Are these one model or two? Deciding this
   is the difference between one coherent configuration document and two
   competing ones.

## Why this cannot be answered by writing the story

The requirement is decided; the model is not. **ADR-VEC-03 (workflow model) is
listed in ADR-VEC-01 as a planned follow-on and has not been written**, and
neither has ADR-VEC-11. Sub-question 3 in particular is not answerable by
analysis — it is a set of interacting cases whose cost only shows up when you try
to build them, which is what makes this a probe rather than a design task. Any
acceptance criteria written for VEC-10 today would be inventing this model in
passing, in a story whose visible deliverable is a web form.

## Scope

In scope:

* A written model covering all six sub-questions, in draft-ADR shape, ready to
  become **ADR-VEC-03**.
* A worked example: the Kanban and Scrum templates from R-CORE-5 expressed
  concretely in the proposed representation, plus one instance carrying a
  realistic delta (a renamed column, an added column, a removed item type).
* A resolution prototype — throwaway — that merges template + delta into an
  effective configuration and demonstrates each evolution case from
  sub-question 3 as a passing or explicitly-rejected case.
* A proposed migration sketch against `V1__initial_schema.sql`.
* An explicit ruling on sub-question 6: one configuration model shared with
  VEC-15, or two.

Explicitly out of scope:

* The provisioning endpoint, the "New Project" form, and the validation
  notifications — **VEC-10** owns those, and becomes merely *complicated* once
  this spike closes.
* Implementing the workflow engine — **VEC-15**.
* Migrating anything on `main`. The prototype is throwaway.
* Template *authoring* UI. Templates being editable by users is a separate
  question; this spike decides the model, and may recommend that v1 ships
  built-in templates only.

## Acceptance criteria

1. A document exists at `docs/spikes/VEC-45-template-instance-model.md`,
   following the shape of `docs/spikes/VEC-13-plugin-loading.md`, answering all
   six sub-questions with a decision, not a survey of options.
2. Sub-question 3 (evolution) is answered case by case — column added, renamed,
   removed, reordered; item type added and removed — each with the stated
   behaviour for an instance that has and has not overridden that element. A
   case marked "not supported in v1" is an acceptable answer; an unlisted case
   is not.
3. The worked Kanban and Scrum templates plus one delta'd instance appear in the
   document in full, in the proposed representation. A reader must be able to
   see the actual bytes, not a description of them.
4. The resolution prototype is checked in under `docs/spikes/vec-45/` or left on
   its branch and linked, with the command to run it, and it exercises every case
   from criterion 2 as an executable assertion.
5. The document contains a migration sketch naming the tables and columns to add
   to `V1__initial_schema.sql`, and states whether configuration lives as jsonb on
   `workspace` (the `item.fields` precedent) or in dedicated tables, with the
   reason.
6. Sub-question 6 is answered with a single sentence stating whether states and
   policies are one model with VEC-15 or two, and the consequence for VEC-15's
   scope is recorded on VEC-15.
7. The document is in a state where it can be renamed to
   `ADR-VEC-03-workflow-and-template-model.md` and reviewed as an ADR, with a
   Status of Proposed.
8. `mvn -B -ntp verify` on `main` is unaffected.

## Exit condition

4 working days. If the reference-plus-delta model proves unworkable within the
box, recommending **materialized copies for v1, with template evolution deferred
to a later milestone** is a legitimate and closing outcome — it unblocks VEC-10
at a known, recorded cost, rather than leaving it stalled.

## Unblocks

VEC-10 (provisioning), and informs VEC-15 (workflow engine), VEC-11
(re-keying), VEC-28 (which reads the point scale from "the project's JSONB
configuration block" — a block this spike defines).
