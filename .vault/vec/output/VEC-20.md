---
kind: story
space: vec
id: VEC-20
title: Zustand Global Store Optimization with Cache Layering
status: DONE
project: VEC
epic: VEC-3
sprint: VEC-S1
storyPoints: 3.0
priority: P2
labels:
- VECTIS-010
- edition-community
- tier-Core
---

## Description

As an Agile Team Member, I want the workspace interface to load instantly when navigating between different team boards, So that my daily operational tasks are never slowed down by layout loading cycles.

**Developer Notes**
Organize the Zustand state model using fully flat index maps (issuesById, epicsById). Connect local tracking states to browser localStorage or IndexedDB schemas to maintain data access speeds.

---

*Backlog ref: VECTIS-010 · Type: Story · Tier: Core · Planned: Sprint 3*

## Acceptance criteria

- Switching boards must render cached local data immediately while simultaneously validating and fetching updates in the background.
- Confirm that modifying individual row properties triggers localized element re-renders rather than an expensive screen refresh.
- Modifying a single row's properties re-renders only that row's component subtree — verified with a render-count assertion — not the full grid.
