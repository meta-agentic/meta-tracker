---
kind: story
space: vec
id: VEC-18
title: Streaming Stateless JSON Import Utility
status: TO DO
project: VEC
epic: VEC-4
storyPoints: 5.0
priority: P2
labels:
- Sprint-3
- VECTIS-008
- edition-community
- tier-Core
---

## Description

As a Project Administrator, I want to import an entire historical backlog array using a standard JSON file import tool, So that we can onboard teams from legacy tracking tools in seconds without causing system timeouts.

**Developer Notes**
Stream the file chunks to a backend endpoint. Use Jackson's non-blocking parser paired with a Mutiny Multi stream to ingest records into a Hibernate Reactive StatelessSession in configured chunks (e.g., 100 entries per batch).

---

*Backlog ref: VECTIS-008 · Type: Story · Tier: Core · Planned: Sprint 3*

## Acceptance criteria

- Uploading a massive JSON file containing thousands of records must display a real-time progress percentage bar.
- The application must process the dataset reliably without memory spikes or out-of-memory container crashes.
