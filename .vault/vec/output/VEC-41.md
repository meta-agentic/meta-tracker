---
kind: story
space: vec
id: VEC-41
title: Core persistence slice — workspace/board/column/item domain model, schema, and reactive repository
type: Enabler
status: DONE
project: VEC
epic: VEC-1
priority: P2
labels: []
---

## Description

Vectis has no persistence and no domain model. Verified on `main` (23078e8): 15 Java files, **zero** SQL or migration files, no schema, and exactly one REST endpoint (`ExtensionDiagnosticsResource`, extension diagnostics only). There is no `Workspace`, `Board`, `Column` or `Item` type anywhere in the codebase.

This slice is **presupposed by two existing stories but owned by neither** — VEC-10 (template provisioning implies persisted workspaces and boards) and VEC-15 (a workflow engine implies items to move). It is the long pole on the critical path to dogfooding: every other dogfood step queues behind it.

## Scope

* Domain types: `Workspace`, `Board`, `Column`, `Item` — framework-free, no persistence annotations leaking into the model.
* Time-ordered identifiers applied for real, per the VEC-9 spike outcome (UUIDv7), rather than a database sequence.
* Schema with the schema-flexible shape the README commits to: relational structure alongside a JSONB payload, so workflows stay data rather than hard-coded columns.
* Versioned migrations (none exist today, so the migration tool choice is part of this).
* Reactive, non-blocking repository over the reactive PostgreSQL client — no JDBC on the request path, consistent with the stated Mutiny end-to-end principle.
* Tests covering round-trip persistence and ordering guarantees.

## Out of scope

REST resources (VEC-10), the workflow engine and drop guardrails (VEC-15), import (VEC-18/19), and any UI wiring. This delivers the model, the schema and the repository only.

## Acceptance

* An item can be persisted and read back, with time-ordered identifiers observably sorting chronologically.
* Migrations run from empty to current on a clean database.
* The domain module stays framework-free; persistence concerns live in the adapter.
* Tests pass in the reactor build.
