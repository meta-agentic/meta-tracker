---
kind: story
space: vec
id: VEC-33
title: Personalized User Profile & Dynamic Workspace Preferences Engine
status: TO DO
project: VEC
epic: VEC-7
storyPoints: 5.0
priority: P2
labels:
- Sprint-6
- VECTIS-024
- edition-community
- tier-Core-Profile
dependencies:
- VEC-35
---

## Description

As an Agile Team Member, I want to customize and persist my personal workspace configurations—such as visual themes, language localizations, and notification rules, So that I can interact with the tracking board in an environment tailored to my visual comfort and regional formatting without resetting my settings between login sessions.

**Acceptance Criteria**

* The interface must provide a 'User Settings' profile panel allowing instantaneous toggling between Light and Dark visual modes.
* Modifying the localization dropdown must instantly swap the UI text strings, data timestamps, and Gantt chart timeline intervals to match the chosen regional locale without a hard page reload.
* The notification dashboard must allow granular opt-in/opt-out checkmarks for different delivery channels mapped to specific system actions.
* Preference profiles must be entirely isolated by tenant constraints and securely tied to the user identity claim extracted from the client's OIDC access token.

**Developer Notes**
Backend Engine: Map a UserPreference entity linked directly to the user's secure account ID. To handle evolving client preference attributes without triggering frequent schema migrations, store these settings inside a single PostgreSQL JSONB database column. Frontend State Architecture: Fetch user preferences on application boot right after token verification and inject into the Zustand store.

---

*Backlog ref: VECTIS-024 · Type: Story · Tier: Core Profile · Planned: Sprint 5 Frontend*
