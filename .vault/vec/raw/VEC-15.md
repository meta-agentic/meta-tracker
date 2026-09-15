---
kind: story
space: vec
id: VEC-15
title: Schema-less Workflow Engine & Card Drop Guardrails
status: TO DO
project: VEC
epic: VEC-2
storyPoints: 8.0
priority: P2
labels:
- Sprint-2
- VECTIS-005
- edition-community
- tier-Core
dependencies:
- VEC-10
---

## Description

As a Scrum Master, I want the system to enforce our team's designated status transit rules directly on the interface, So that tasks cannot bypass critical review columns or drop into arbitrary state steps.

**Acceptance Criteria**

* Dragging a card to an unauthorized column must be rejected visually, resetting the card to its original place with a helpful explanation.
* Direct API modifications bypassing the UI layout must receive an identical validation block.

**Developer Notes**
Read allowed workflow paths from the project's JSONB configuration payload. Validate changes on the backend using a reactive filter chain (Uni.onItem().transformToUni(...)) prior to saving transactions.

---

*Backlog ref: VECTIS-005 · Type: Story · Tier: Core · Planned: Sprint 2*
