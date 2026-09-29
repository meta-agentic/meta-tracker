---
kind: bug
space: vec
id: VEC-52
title: Board column headers render the literal "({{value}})" instead of the item count
status: DONE
project: VEC
epic: VEC-3
priority: P2
labels:
- web
- i18n
dependencies: []
sprint: VEC-S4
relates:
- VEC-34
storyPoints: 1
estimate:
  extension: -0.8
  intension: -0.5
  quadrant: simple
  basis: up-front
  dated: '2026-09-28'
  note: one key mismatch plus a test
---

## Problem

`web/src/components/BoardView.tsx` calls `t("board.columnCount", { count: ... })`, but the English and Italian strings are `"({{value}})"`. The interpolation key does not match, so every column header shows the placeholder text. Seen on `main` (2026-09-28) while running the client against a local server.

## Acceptance criteria

- [ ] Column headers show the number of items in the column, in every shipped locale.
- [ ] A test renders a column and asserts the count, so a key mismatch fails CI.
