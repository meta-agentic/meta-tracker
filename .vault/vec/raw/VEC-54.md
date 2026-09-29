---
kind: story
space: vec
id: VEC-54
title: Vectis shows its own backlog — read-only vault import behind the connector contract
status: TO DO
project: VEC
epic: VEC-47
priority: P0
labels:
- showcase
- adapter
dependencies:
- VEC-53
- VEC-10
sprint: VEC-S4
storyPoints: 2
estimate:
  extension: 0.0
  intension: -0.1
  quadrant: simple
  basis: up-front
  dated: '2026-09-29'
  note: 're-pointed after the PO-approved split: wiring only (upsert, endpoint, local run)'
---

## Use case

As a visitor to the public repository, I want to run Vectis and see the project's real backlog
on its board, so that the tracker demonstrates itself on real data rather than demo rows.

## Acceptance criteria

- [ ] A read-only vault connector implements the contract from VEC-53 and imports `.vault/vec`
      into a workspace `VEC`: every item appears once, in the column its status maps to.
- [ ] Re-running the import changes nothing; an edited item file updates its item.
- [ ] One command brings the stack up locally (database, server, web) and the board shows the
      real backlog; documented in the README.
- [ ] Tests cover the mapping and idempotency without a live database where possible.

## Open points from the VEC-53 spike (2026-09-28)

- **PO decision (2026-09-28): REFINED stays REFINED.** It is not folded into To Do or Backlog:
  the imported board carries its own *Refined* column between Backlog and To Do (PLANNED still
  maps to To Do). Expressed as an instance delta on the Scrum template once VEC-45's model lands;
  if VEC-45 re-refines how that is represented, that is accepted.
  **What REFINED means (PO):** the item passed refinement and is ready — normally picked up in the
  next sprint. If it waits too long, other changes can supersede it and it needs refining again;
  that should be rare while the ready queue stays short. So the column reads as *ready for the next
  sprint*, distinct from To Do. Follow-up idea (not in this story): flag a REFINED item that has
  waited more than about one sprint as "may need re-refinement".
- **Unverified:** the YAML parsing library in the native build — check before relying on the import in a native image.
- `.vault/README.md` lines 85–87 ("nothing in the application reads these files") must be reworded in this story's PR.

## Split 2026-09-28

The read side (contract, parser, mapping, tests) moved to VEC-57 so it can run while VEC-10 waits on VEC-45. This story keeps the wiring: the idempotent persistence upsert, `POST /api/v1/imports/{sourceId}`, the one-command local run and the `.vault/README.md` rewording.
