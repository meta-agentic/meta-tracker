---
kind: story
space: vec
id: VEC-27
title: Cumulative Flow Diagram (CFD) Inefficiency Engine
status: TO DO
project: VEC
epic: VEC-6
storyPoints: 8.0
priority: P2
labels:
- Sprint-7
- VECTIS-017
- edition-community
- tier-Core-Analytics
dependencies:
- VEC-15
---

## Description

As a Scrum Master, I want to view a Cumulative Flow Diagram of our work item states over custom time horizons, So that I can instantly spot systemic bottlenecks, widening work-in-progress (WIP) bands, and scope creep.

**Acceptance Criteria**

* The UI must render a stacked area chart showing the total count of items in each workflow state for every single day of the selected timeframe.
* Changing filter criteria (by project tag, epic, or issue type) must update the chart layout within 300 milliseconds.
* The visualization must handle data stretching back over 12 months without dropping render frame rates.

**Developer Notes**
Leverage the transition history ledger generated in Sprint 2. Write an optimized PostgreSQL window function query to calculate a running daily total per status. Map the data arrays to a high-performance chart canvas (like Recharts) in the frontend React app.

---

*Backlog ref: VECTIS-017 · Type: Story · Tier: Core Analytics · Planned: Sprint 6*
