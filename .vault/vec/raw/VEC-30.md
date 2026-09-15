---
kind: story
space: vec
id: VEC-30
title: Executive Multi-Project Portfolio Health Dashboard
status: TO DO
project: VEC
epic: VEC-6
storyPoints: 8.0
priority: P2
labels:
- Sprint-8
- VECTIS-020
- edition-community
- tier-Core-Analytics
dependencies:
- VEC-24
---

## Description

As a Program Manager, I want a macro-level portfolio status interface aggregating performance scores across all independent project tags, So that I can report our overall delivery posture to leadership without having to review individual team boards manually.

**Acceptance Criteria**

* The dashboard grid must display structured metric summaries for each active project layout, detailing total open items, overall completion percentage, and active cross-project blockers.
* Selecting any specific project card from the executive grid view must seamlessly deep-link the user back down to that team's explicit hierarchical backlog.

**Developer Notes**
This cross-cutting view must query across individual project boundaries. Optimize performance by utilizing custom JPQL database projections to combine issue counts and unresolved dependency records efficiently in a single non-blocking backend pass.

---

*Backlog ref: VECTIS-020 · Type: Story · Tier: Core Analytics · Planned: Sprint 7*
