---
kind: story
space: vec
id: VEC-23
title: Synchronized Dual-Pane Ledger and Gantt Roadmap Canvas
status: REFINED
project: VEC
epic: VEC-3
storyPoints: 5
priority: P2
labels:
- Sprint-4
- VECTIS-013
- edition-community
- tier-Core
dependencies:
- VEC-21
- VEC-22
estimate:
  extension: 0.5
  intension: -0.3
  quadrant: complicated
  basis: up-front
  dated: '2026-08-30'
  note: 'Complicated, not complex — and NOT the 8 it carried. High extension: a composed view, a shared
    scroll/row coordinator, edge-drag handlers with date writeback, an App tab, and alignment/virtualization
    test coverage. Low intension: TimelineGrid (VEC-21, DONE) already drives two virtualizers off one
    shared scroll element, so a third consumer of the same offset is the pattern it already uses; Issue.startDate/dueDate
    already exist to bind and write back to. Scoped down from the original by ruling bar-move, dependency
    arrows and zoom levels out. Web-only, no server. Depends on VEC-22 landing first.'
---

## Problem

The two halves of a roadmap view exist separately and have never been joined.
`src/profiling/TimelineGrid.tsx` (VEC-21, **DONE**) is a dual-axis virtualized
day grid; the backlog tree arrives with **VEC-22**. Neither knows about the
other, and today the timeline lives inside a profiling harness rather than a
product view. A Project Director cannot read scope and dates side by side.

Two things make this an assembly job rather than an invention. First,
`TimelineGrid` already drives **two virtualizers off a single scroll element** —
the row virtualizer and the day-column virtualizer read the same container — so
sharing a vertical offset with a third virtualizer is the pattern it already
uses, not a new one. Second, `Issue` in `src/store/types.ts` already carries
`startDate: string | null` and `dueDate: string | null`, so a timeline bar has
real fields to bind to and to write back to.

## Scope

In scope:

* A `RoadmapView` under `src/components/` composing the VEC-22 `BacklogTree` in a
  left pane and `TimelineGrid` in a right pane, as a product tab in
  `src/App.tsx` — not inside the profiling harness.
* A shared vertical-scroll coordinator so both panes render the same row window:
  one source of truth for the vertical offset and the row set, consumed by both
  virtualizers. Row identity and row height must agree between the panes by
  construction, not by both happening to use the same constant.
* Expand/collapse in the tree adds and removes the corresponding timeline rows in
  the same frame — the two panes never disagree about which rows exist.
* Drag a bar's left or right edge to change `startDate` / `dueDate`, committing
  through the store's existing `upsertIssue`.
* Horizontal scrolling of the timeline pane leaving the tree pane fixed.

Explicitly out of scope:

* **Dragging a whole bar to move it**, and dependency arrows between bars. Edge
  resize only, per the stated acceptance criteria.
* Server persistence of the date change — the write goes to the store; the
  transport binding is **VEC-42**, and item mutation endpoints are **VEC-16**.
* Rows without dates. An issue with `startDate: null` renders as a tree row with
  an empty timeline track; inventing a placeholder range is out of scope.
* Zoom levels (week/month/quarter). The grid renders days, as `TimelineGrid`
  does today.
* Retiring `ProfilingHarness` — it stays, and must keep working standalone.

## Acceptance criteria

1. A roadmap tab renders tree and timeline side by side, both fed from the same
   ordered row array produced by VEC-22's `selectFlatTree`. Neither pane derives
   its own row order.
2. Scrolling either pane vertically moves the other to the same row offset, with
   rows aligned — the *n*th tree row and the *n*th timeline track share a top
   edge. Asserted in a vitest test comparing the two panes' reported offsets, not
   by eye.
3. Expanding or collapsing a tree node changes both panes' row sets together;
   there is no frame in which the tree shows a row the timeline does not, or the
   reverse.
4. Scrolling the timeline horizontally does not move the tree pane.
5. Dragging a bar's edge updates the issue's `startDate` or `dueDate` in the
   store, the bar settles on a whole-day boundary, and the change is visible in
   any other view reading the same issue without a reload.
6. A drag that would put the end before the start is rejected and the bar returns
   to its prior geometry; no store write occurs.
7. Both panes stay virtualized when composed: with 4,000 issues expanded, the
   rendered node count in each pane stays bounded by the viewport, asserted
   through the `onRenderStats` hook `TimelineGrid.tsx` already exposes.
8. Vertical position is maintained by transform, not by mutating layout
   properties — no `padding` or `top` reflow per scroll frame — matching how
   `TimelineGrid` positions today.
9. `ProfilingHarness` still runs standalone against generated data, and
   `TimelineGrid.test.tsx` and `profiler.test.ts` pass untouched.

## Verification

From `web/`:

```
npm run test
npm run typecheck
npm run lint
```

Criterion 7 is additionally checkable through the existing profiler path at the
VEC-21 presets.

## Requirement trace

R-UX-4 (high-density virtualization; large timelines render without pagination;
60 fps at 2,500 items x 365 days) from ADR-VEC-01 §2. Roadmap M4.

## Dependencies

VEC-21 — **DONE**. VEC-22 — **REFINED**, must land first; this story consumes
its `selectFlatTree` and `BacklogTree` directly and cannot start before them.
No server dependency: like VEC-22 and VEC-34, this is buildable against the
store in the current bootstrap state.
