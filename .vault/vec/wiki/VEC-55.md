---
kind: story
space: vec
id: VEC-55
title: SPA design pass — app shell, board cards, item detail and states
status: IN PROGRESS
project: VEC
epic: VEC-3
priority: P0
labels:
- showcase
- web
- design
dependencies: []
relates:
- VEC-54
sprint: VEC-S4
storyPoints: 5
estimate:
  extension: 0.4
  intension: -0.1
  quadrant: complicated
  basis: up-front
  dated: '2026-09-28'
  note: 'broad but known: shell, cards, detail panel, states, two themes'
---

## Use case

As a visitor, I want the web client to look like a finished product, so that the first minute
with Vectis reads as craft, not scaffolding.

## Acceptance criteria

- [ ] An app shell with the workspace name and navigation that reads as a product.
- [ ] Board cards show key, title, type, points, epic and labels, with a clear visual hierarchy.
- [ ] Clicking a card opens an item detail panel with its description and links.
- [ ] Loading, empty, stale and error states are designed, not plain text.
- [ ] Light and dark themes both hold up; checked in the browser at desktop and narrow widths.
