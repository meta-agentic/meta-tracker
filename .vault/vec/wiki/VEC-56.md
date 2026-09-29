---
kind: task
space: vec
id: VEC-56
title: Public front page reflects reality — README status, ROADMAP and .vault snapshot
status: IN PROGRESS
project: VEC
epic: VEC-1
priority: P0
labels:
- showcase
- docs
dependencies:
- VEC-54
- VEC-55
sprint: VEC-S4
storyPoints: 2
estimate:
  extension: -0.3
  intension: -0.3
  quadrant: simple
  basis: up-front
  dated: '2026-09-28'
  note: docs and a snapshot refresh, gated on PO-approved text
---

## Why

The README still says "bootstrap / planning, not yet usable"; ROADMAP.md is the June original
with every line "planned"; the in-repo `.vault/` snapshot stopped on 2026-09-15.

## Acceptance criteria

- [ ] README leads with what works, how to run it, and a screenshot of Vectis showing its own backlog.
- [ ] ROADMAP.md marks delivered and in-progress work (draft exists on `docs/roadmap-refresh`).
- [ ] `.vault/` refreshed from the planning copy, checked for instance data before commit.
- [ ] Every change goes through a PR whose exact text the PO approved.
