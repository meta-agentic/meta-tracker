---
kind: story
space: vec
id: VEC-43
title: Sprint mechanics — create, start and complete a sprint, with backlog/sprint scope
status: DONE
project: VEC
epic: VEC-2
priority: P2
labels: []
sprint: VEC-S2
storyPoints: 8
estimation:
  extension: 0.6
  intension: 0.5
  quadrant: complex+broad
---

## Description

The README commits Vectis to "workspaces, boards, sprints, and real-time collaboration", but **nothing in the backlog delivers sprint mechanics.** VEC-28 delivers a burndown *visualizer* and VEC-31 collaborative estimation — both consume sprints that no story creates. There is no sprint entity, no start/complete lifecycle, and no notion of sprint versus backlog scope.

## Not on the dogfooding critical path — filed to close the gap, not to schedule it

VEC currently runs **without sprints**: a `sprint in openSprints()` query across the whole site returns 84 issues in IOS, MOS and IAM only, and none in VEC. So Vectis can hold the VEC backlog and be genuinely dogfooded before this exists.

It does, however, block dogfooding any of the other spaces, and it blocks VEC-28 and VEC-31 regardless of sequencing. Filing it so the dependency is visible rather than discovered later.

## Scope

* Sprint entity with a lifecycle: future → active → completed.
* Assign and remove items from a sprint; a backlog scope distinct from sprint scope.
* Completing a sprint moves unfinished items somewhere explicit (next sprint or backlog) rather than silently.
* Whether several sprints may be active at once is a real design decision, not an implementation detail — Jira makes it an opt-in board setting, and the estate's per-space cadence is the reason the question matters. Decide it here.

## Acceptance

* A sprint can be created, started and completed against a board.
* Items can be moved between backlog and sprint scope.
* Unfinished items at completion land in a defined destination.
* The active-sprint cardinality rule is documented and enforced server-side.

## Estimate

**extension +0.6** (new domain entity + lifecycle, new persistence layer +
migration, touches the existing `Item` type, ~10 files across domain/
persistence/tests) · **intension +0.5** (the story's own scope names a real
open design decision — sprint cardinality — plus transition-legality turned
out to be a second one, found by review after the DB-enforced cardinality
invariant landed) → **complex + broad → 8**, this story's original full scope.

Correctly **not committed unsplit**: the architect scoped this delivery to
domain + persistence only, explicitly deferring the REST API (no entity has
one yet — adding the first inside this PR would invent an estate-wide
convention unreviewed) and the frontend sprint UI (blocked on that API). That
is the prescribed move for this quadrant, applied inline rather than as a
separate PO-visible split beforehand. The 8 points this row carries are for
the slice actually delivered; REST API and frontend UI are real remaining
scope, not folded into this estimate, and want their own item(s) when
picked up.
