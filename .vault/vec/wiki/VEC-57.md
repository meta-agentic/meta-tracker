---
kind: story
space: vec
id: VEC-57
title: Vault import, read side — BacklogSource contract, .vault parser and status mapping
status: IN PROGRESS
project: VEC
epic: VEC-47
priority: P0
labels:
- showcase
- adapter
dependencies:
- VEC-53
relates:
- VEC-54
sprint: VEC-S4
storyPoints: 3
estimate:
  extension: 0.1
  intension: -0.2
  quadrant: complicated
  basis: up-front
  dated: '2026-09-29'
  note: split from VEC-54 after the VEC-53 spike settled the design
---

## Why

Split from VEC-54 on 2026-09-28 (PO-approved) so the part that does not need VEC-10's workspace endpoints can start now. VEC-54 keeps the wiring: persistence upsert, the import endpoint and the one-command local run.

## Scope

The read-only `BacklogSource` contract in the extension SPI and a new community module that parses `.vault/<space>` into a `BacklogSnapshot`, as designed in VEC-53. No database, no server endpoint, no SPA change.

## Acceptance criteria

- [ ] `BacklogSource` (id, available, read → snapshot) in the SPI, pure-JDK types, with javadoc; `SyncConnector` untouched.
- [ ] A new module parses every item file under `.vault/<space>/{raw,wiki,output}`, ignoring generated `_index.md` files, into snapshot items with key, title, kind, status category, epic, points, sprint, labels and links.
- [ ] Status → column mapping per the design, with REFINED as its own category (PO: REFINED stays REFINED); an unknown or contradictory status is placed by directory tier and reported as a problem, never dropped.
- [ ] Unit tests on fixture vault trees cover every mapping row, malformed front-matter, a missing directory and `_index.md` exclusion; the module builds and tests without Docker or a database.
