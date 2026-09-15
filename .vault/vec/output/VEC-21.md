---
kind: story
space: vec
id: VEC-21
title: Dual-Axis Performance Profiling for Large Timelines
status: DONE
project: VEC
epic: VEC-3
sprint: VEC-S1
storyPoints: 1.0
priority: P2
labels:
- VECTIS-011
- edition-community
- spike
- tier-Core
---

## Description

As a Portfolio Lead, I want to verify that rendering thousands of scheduled tasks across long multi-year timelines won't crash my web browser, So that I can manage long-range roadmaps with complete confidence.

**Developer Notes**
Analyze @tanstack/react-virtual performance metrics when virtualizing vertical rows and horizontal timeline columns simultaneously. Keep DOM element layouts flat and clean.

---

*Backlog ref: VECTIS-011 · Type: Spike · Tier: Core · Planned: Sprint 4*

## Acceptance criteria

- Run performance profiles on a mock view containing 2,500 active tasks displayed across a 365-day layout.
- Verify the rendering interface maintains a steady performance score of 60 frames per second during continuous scrolling actions.
- The profiling run captures a baseline artifact (flamegraph / metrics snapshot), committed for regression comparison by later timeline work.
