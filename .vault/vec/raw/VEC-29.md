---
kind: story
space: vec
id: VEC-29
title: Cycle Time & Lead Time Percentile Histogram
status: TO DO
project: VEC
epic: VEC-6
storyPoints: 5.0
priority: P2
labels:
- Sprint-8
- VECTIS-019
- edition-community
- tier-Core-Analytics
dependencies:
- VEC-15
---

## Description

As a Delivery Lead, I want to analyze our work items' cycle times represented as an empirical percentile distribution histogram, So that I can confidently quote predictable SLA turnaround times to stakeholders.

**Acceptance Criteria**

* The system must display a histogram chart tracking the total elapsed days a card spent between entering an active development column and landing in a Done column.
* The analytics engine must automatically calculate and display the 50th, 85th, and 95th performance percentiles for the current dataset.

**Developer Notes**
Write a reactive query utilizing Mutiny to extract differences between a card's PROGRESS and DONE log timestamps. Build a utility function in the Zustand store to bin these durations into standard distribution array buckets for the presentation layer.

---

*Backlog ref: VECTIS-019 · Type: Story · Tier: Core Analytics · Planned: Sprint 7*
