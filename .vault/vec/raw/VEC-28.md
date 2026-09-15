---
kind: story
space: vec
id: VEC-28
title: Historical Velocity & Dynamic Sprint Burndown Visualizer
status: TO DO
project: VEC
epic: VEC-6
storyPoints: 5.0
priority: P2
labels:
- Sprint-7
- VECTIS-018
- edition-community
- tier-Core-Analytics
dependencies:
- VEC-15
---

## Description

As a Product Owner, I want to track our team's historical velocity alongside a real-time sprint burndown chart, So that I can accurately forecast delivery dates for future milestone releases based on actual performance.

**Acceptance Criteria**

* The sprint burndown view must display an 'ideal trend line' compared against an 'actual remaining points line,' updating automatically whenever a card shifts to a completed column.
* The velocity bar chart must display a rolling history of committed versus completed story points over the last 5 sprint iterations.

**Developer Notes**
Pull the active calculation parameters directly from the project's JSONB configuration block (e.g., verifying if the team uses a FIBONACCI or linear point scale). To minimize performance hits on live transactional tables, compute the daily burndown snapshots using a scheduled backend worker thread or cache updates incrementally using Redis.

---

*Backlog ref: VECTIS-018 · Type: Story · Tier: Core Analytics · Planned: Sprint 6*
