---
kind: story
space: vec
id: VEC-16
title: High-Velocity Inline Task Grid & Basic CRUD Actions
status: TO DO
project: VEC
epic: VEC-3
storyPoints: 5.0
priority: P2
labels:
- Sprint-2
- VECTIS-006
- edition-community
- tier-Core
dependencies:
- VEC-10
---

## Description

As an Agile Team Member, I want to quickly create, view, modify, and delete tasks directly inside an inline grid layout, So that I can update my task list during rapid planning sessions without opening full modal dialogues.

**Acceptance Criteria**

* Pressing Enter on an active row must commit text updates and automatically create a new blank row underneath.
* Double-clicking a field cell must toggle it into a direct text input mode.

**Developer Notes**
Build a clean, keyboard-accessible table layout using React. Connect input field blur and focus adjustments to a debounced update service that hits the backend reactive PATCH endpoints.

---

*Backlog ref: VECTIS-006 · Type: Story · Tier: Core · Planned: Sprint 2*
