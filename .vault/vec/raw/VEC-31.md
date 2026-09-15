---
kind: story
space: vec
id: VEC-31
title: Real-Time Collaborative Estimation Poker Widget
status: TO DO
project: VEC
epic: VEC-5
storyPoints: 5.0
priority: P2
labels:
- Sprint-7
- VECTIS-021
- edition-community
- tier-Core-Collaborative
dependencies:
- VEC-17
---

## Description

As a Scrum Team Member, I want to cast my story point estimate within a synchronized, timed voting window—or explicitly signal that the item requires more analysis, So that our team can collaboratively reach consensus during backlog refinement without context-switching to third-party polling apps.

**Acceptance Criteria**

* When a session coordinator initiates a vote on a chosen work item, an interactive widget must instantly appear on the screens of all logged-in team members viewing that board.
* The widget must display a real-time countdown timer (e.g., 60 seconds). Individual point selections must remain hidden (facedown) until the timer runs out or the coordinator forces a reveal.
* The voting card deck must display your active point scale values (1, 2, 3, 5, 8, 13, 21) alongside a prominent 'Request Refinement' button.
* If any team member selects 'Request Refinement,' the final revealed state must visibly flag that consensus was blocked due to a lack of technical clarity.
* The casting of votes must be entirely non-blocking and populate the UI dynamically via streaming updates.

**Developer Notes**
Extend the Server-Sent Events (SSE) system established in Sprint 2 to broadcast real-time estimation room state transitions. Manage the active session in a lightweight application-level reactive cache or in-memory state engine in the Quarkus backend, writing only the final agreed-upon story point integer to the database when the session is closed.

---

*Backlog ref: VECTIS-021 · Type: Story · Tier: Core Collaborative · Planned: Sprint 5*
