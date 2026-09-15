---
kind: story
space: vec
id: VEC-11
title: Live Project Rebranding & Prefix Synchronization
status: TO DO
project: VEC
epic: VEC-2
storyPoints: 3.0
priority: P2
labels:
- Sprint-3
- VECTIS-003
- edition-community
- tier-Core
dependencies:
- VEC-10
---

## Description

As a Product Owner, I want to modify our project display tag (e.g., IOS to SPACE) from the administration page, So that our task identifiers stay aligned with changing brand guidelines without breaking cross-card dependencies.

**Acceptance Criteria**

* Saving a new project key must instantly update all user-facing task cards (e.g., PROJ-101 becomes SPACE-101).
* Deep links or mentions using the old key prefix must seamlessly redirect to the updated identifier.

**Developer Notes**
Decouple internal keys from human-readable labels. Foreign keys must anchor to immutable database UUIDs, while the mutable display string prefix uses an indexed database column.

---

*Backlog ref: VECTIS-003 · Type: Story · Tier: Core · Planned: Sprint 1*
