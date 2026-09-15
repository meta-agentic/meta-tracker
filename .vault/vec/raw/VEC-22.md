---
kind: story
space: vec
id: VEC-22
title: Expandable Hierarchical Backlog Tree Explorer
status: REFINED
project: VEC
epic: VEC-3
storyPoints: 5
priority: P2
labels:
- Sprint-4
- VECTIS-012
- edition-community
- tier-Core
dependencies:
- VEC-20
- VEC-21
estimate:
  extension: 0.4
  intension: -0.4
  quadrant: complicated
  basis: up-front
  dated: '2026-08-30'
  note: 'Complicated, not complex: assembly over settled parts. Extension moderate-high — a new selector,
    a new virtualized component, a store slice for expansion state, an App tab, and test coverage across
    all of them. Intension low: Issue.epicId already models the parent link, selectors.ts sets the derived-view
    convention, and VEC-21 (DONE) already proved @tanstack/react-virtual at scale in TimelineGrid. The
    one genuinely undefined part — the Initiative level — has no data model and was scoped OUT to VEC-24,
    leaving the remainder known. Web-only, no server dependency. Anchor: VEC-20 (store+cache) 3.'
---

## Problem

The SPA can render a **board** (`src/components/BoardView.tsx`, columns of
cards) and a **timeline** (`src/profiling/TimelineGrid.tsx`, dual-axis
virtualized), but it cannot render the backlog as a **hierarchy**. A Product
Owner cannot see epics with their stories nested underneath, expand or collapse
a branch, or edit a title in place from that view.

The pieces this needs already exist and are proven:

* `src/store/types.ts` models the parent link — `Issue.epicId: ID | null` — and
  `Epic` is a first-class entity in the store.
* `src/store/selectors.ts` establishes the derived-view convention: pure
  functions over the flat normalized maps, never a scan of the whole workspace.
* VEC-21 (**DONE**) proved `@tanstack/react-virtual` windows tens of thousands of
  rows in `TimelineGrid.tsx`, with `onRenderStats` as the DOM-node assertion
  hook.
* VEC-20 (**DONE**) provides the cache-layered store the expansion state will
  persist through.

So this is an assembly job over settled parts, not an invention. What is missing
is the flattening selector, the tree component, and inline editing.

## Scope

In scope:

* A pure selector in `src/store/selectors.ts` — `selectFlatTree(state, boardId,
  expanded)` — that projects the nested epic → issue structure into a flat
  ordered array of `{ id, kind, depth, parentId, hasChildren }` rows, containing
  **only** rows currently visible under the given expansion set. Depth-generic:
  it must not hardcode two levels (see *Third level*, below).
* A `BacklogTree` component under `src/components/`, virtualizing that flat array
  with `@tanstack/react-virtual`, following the pattern `TimelineGrid.tsx`
  already sets.
* Expand/collapse per row, with the expansion set held in the store and persisted
  through the existing cache layer so it survives a reload.
* Inline title editing: focus a title cell, edit, and commit on blur via the
  store's existing `upsertIssue`.
* A third tab alongside "Boards" and "Timeline profiler" in `src/App.tsx`.

Explicitly out of scope:

* **The Initiative level.** `src/store/types.ts` models two levels only (`Epic`,
  `Issue`); there is no Initiative entity, and cross-workspace initiative linkage
  is **VEC-24**, which is unrefined. This story ships epic → story and makes the
  selector depth-generic so VEC-24 adds a level as data, not as a rewrite.
  Claiming "Initiatives → Epics → Stories" today would be claiming a data model
  that does not exist.
* Drag-to-reparent, multi-select, and bulk edit.
* The synchronized dual-pane Gantt — that is **VEC-23**, which depends on this.
* Server persistence of the edit. Like every SPA story in the current bootstrap
  state, this runs against the store; wiring the store to a real backend is
  **VEC-42**.

## Acceptance criteria

1. `selectFlatTree` is a pure function of `(state, boardId, expandedIds)` living
   in `src/store/selectors.ts`, returning only visible rows. Collapsing a parent
   removes its whole subtree from the returned array — the component never
   filters.
2. The selector is depth-generic: adding a level above `Epic` requires changing
   the level descriptor, not the traversal. Proven by a unit test that builds a
   synthetic three-level fixture and asserts correct flattening.
3. Expanding or collapsing a row does not change the scroll offset of any row
   that remains visible above the toggled row. Asserted in a vitest test on the
   virtualizer's `scrollOffset`, not by eye.
4. Rendering is virtualized: with 4,000 issues fully expanded, the number of
   rendered row nodes stays bounded by the viewport, asserted through the same
   `onRenderStats`-style hook `TimelineGrid.tsx` exposes — not by a screenshot.
5. Editing a title and moving focus away commits it through the store; the new
   title is visible in `BoardView` without a reload, since both read the same
   normalized map. Reverting focus without a change fires no store write.
6. Pressing `Escape` during an edit abandons it and restores the previous title.
7. The expansion set and the active tab survive a page reload, restored via the
   store's existing cache layer (`src/store/cache.ts`).
8. The board and timeline tabs are behaviourally unchanged — the existing
   `workspaceStore.test.ts`, `TimelineGrid.test.tsx` and `profiler.test.ts`
   suites pass untouched.

## Verification

From `web/`:

```
npm run test        # vitest — includes the new selector and tree tests
npm run typecheck
npm run lint
```

## Requirement trace

R-UX-4 (high-density virtualization; large trees render without pagination) and
R-UX-1 (keyboard-first inline editing) from ADR-VEC-01 §2. Roadmap M4.

## Dependencies

VEC-20 and VEC-21 — both **DONE**. This story needs no server and no API, which
makes it pullable in the current bootstrap state. **VEC-23** (dual-pane
ledger + Gantt) depends on it; **VEC-24** (initiative roll-up) is what unlocks
the third level.
