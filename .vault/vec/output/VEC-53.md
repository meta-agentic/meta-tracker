---
kind: spike
space: vec
id: VEC-53
title: 'SPIKE — read-only vault adapter: contract and mapping from .vault to a workspace'
status: DONE
project: VEC
epic: VEC-47
priority: P0
labels:
- showcase
- adapter
- spike
dependencies: []
relates:
- VEC-10
sprint: VEC-S4
storyPoints: 2
estimate:
  extension: -0.4
  intension: 0.5
  quadrant: complex
  basis: up-front
  dated: '2026-09-28'
  note: 'spike: contract and mapping are the unknowns, the code to read them is small'
---

## Why

meta-tracker is public, but it cannot show a single real item. The showcase slice makes Vectis display its own backlog (`.vault/`, 51 items). That needs the adapter contract VEC-47 describes; this spike fixes the read-only half of it before any code.

## Questions it must answer

1. The read contract on `SyncConnector` (today only `id()` and `available()`): the smallest interface that lets a connector list work items with their status, kind, epic, points and sprint — without committing to write-back or conflict handling.
2. The mapping from vault items to the domain: tier/status → board column, `kind` → item type, epics, sprints, `dependencies`/`relates`, ids → item keys (`VEC-42` stays `VEC-42`).
3. When the import runs (startup, on demand, file watch) and how it is idempotent — re-running it must not duplicate items; items keep stable ids.
4. Where the adapter lives (a module beside the SPI or inside the server) and how the SPA reaches the result (the VEC-10 read endpoints are enough, or not).

## Acceptance criteria

- [x] A design note in the vault against this item, and an ADR draft for `docs/adr/` (public wording: no private or environment-specific details), each answer grounded in the code as it stands.
- [x] VEC-54 re-pointed on the evidence.

Time-box: 1 day.

## Outcome 2026-09-28

A design note, and an ADR draft for `docs/adr/` (status Proposed, PO review pending); the design note is not part of this snapshot, and the ADR is published once it is accepted.

- **Contract:** a sibling SPI interface `BacklogSource { id(); available(); BacklogSnapshot read(); }`, pure-JDK types; `SyncConnector` stays as it is (its single-winner resolution does not fit several read-only sources).
- **Mapping:** TO DO→Backlog · REFINED→its own *Refined* column (PO decision 2026-09-28, recorded on VEC-54; supersedes the spike's REFINED→To Do) · PLANNED→To Do · IN PROGRESS→In Progress · IN REVIEW→In Review · DONE and NO GO→Done (`resolution: no-go`); unknown/contradictory status placed by tier and reported. Keys preserved; rank = priority digit + zero-padded key number.
- **Idempotency:** upsert on the (workspace, key) unique constraint, one transaction under a per-workspace lock; ids stay UUIDv7 (deterministic ids rejected, R-CORE-2); only importer-owned items are deleted, never after an empty snapshot.
- **Trigger:** `POST /api/v1/imports/{sourceId}` + optional best-effort startup run; parser in a new community module, off by default. VEC-10's read endpoints suffice for the board.
- **VEC-54 re-pointed:** 5 SP, unchanged (splittable 3 items + 2 sprints).
- **Top risk:** the native image cannot see `.vault/` — delivery to staging (bake in vs git-sync sidecar) is an open decision; dev works. Also: `.vault/README.md` must stop saying nothing reads the files; the import endpoint is unauthenticated until VEC-35.

