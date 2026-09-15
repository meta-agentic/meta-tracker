---
kind: story
space: vec
id: VEC-24
title: Cross-Project Strategic Initiative Roll-up Engine
status: TO DO
project: VEC
epic: VEC-5
storyPoints: 5.0
priority: P2
labels:
- Sprint-5
- VECTIS-014
- edition-community
- tier-Core
---

## Description

As a Program Manager, I want to link Epics from separate project workspaces under a single shared Strategic Initiative, So that I can track overall progress across multiple teams inside a single consolidated dashboard.

**Acceptance Criteria**

* The planning view must support linking an Epic from one workspace and an Epic from another workspace to one Initiative tracker.
* The parent Initiative card must calculate and display an accurate total completion percentage based on the combined story points of all child tasks.

**Developer Notes**
Structure data linkages using an independent mapping collection. Run aggregation calculations directly inside the database query layer via custom JPQL projections to minimize application-side filtering overhead.

---

*Backlog ref: VECTIS-014 · Type: Story · Tier: Core · Planned: Sprint 5*
