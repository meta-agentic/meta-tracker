---
kind: story
space: vec
id: VEC-25
title: Inter-Team Dependency Gatekeeper
status: TO DO
project: VEC
epic: VEC-5
storyPoints: 5.0
priority: P2
labels:
- Sprint-5
- VECTIS-015
- edition-community
- tier-Core
dependencies:
- VEC-15
---

## Description

As a Release Train Engineer, I want the workspace to block a card from moving to Done if it has unresolved dependencies in another team's backlog, So that we prevent broken releases caused by deploying components whose prerequisites are incomplete.

**Acceptance Criteria**

* The system must restrict a card from entering the Done column if an active link indicates it is blocked by an uncompleted task in another project.
* Attempting an invalid move must trigger a clear alert message identifying the blocking cross-project key.

**Developer Notes**
Evaluate issue dependency records within the reactive state validation filter before verifying standard workflow path rules. Use an existential check (exists) to confirm blocking tasks have entered their final valid states.

---

*Backlog ref: VECTIS-015 · Type: Story · Tier: Core · Planned: Sprint 5*
