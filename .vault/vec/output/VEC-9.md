---
kind: story
space: vec
id: VEC-9
title: Time-Ordered Primary Key Performance Matrix
status: DONE
project: VEC
epic: VEC-1
sprint: VEC-S1
storyPoints: 1.0
priority: P2
labels:
- Sprint-1
- VECTIS-001
- edition-community
- spike
- tier-Core
---

## Description

As an Agile Team Member, I want to guarantee that our board never experiences record locking or index degradation during massive distributed planning sessions, So that I can update my task parameters instantly without waiting on relational table queues.

**Developer Notes**
Evaluate UUIDv7 vs. ULID generation performance using Mutiny streams. Ensure generation takes place entirely on the application side to preserve non-blocking transaction cycles in the database layer.

---

*Backlog ref: VECTIS-001 · Type: Spike · Tier: Core · Planned: Sprint 1*

## Acceptance criteria

- Ordering guarantee: application-generated IDs (UUIDv7 or ULID) sort chronologically by creation time — for N items created in sequence, ascending primary-key order equals creation order (property test, >=10,000 items).
- Throughput target: sustain 100,000 concurrent write requests (ADR-VEC-01 R-CORE-2) against reactive PostgreSQL via a Quarkus reactive container, recording p50/p95/p99 insert latency.
- Index locality / no fragmentation: after the 100k load, B-tree index bloat stays below an agreed threshold (e.g. <20%) and no central-sequence write hotspot appears, benchmarked against a bigserial baseline.
- Decision output: the spike concludes with a recorded recommendation (UUIDv7 vs ULID) and rationale, since downstream stories inherit this identity choice.
- Back-compat / migration: the chosen identifier is 128-bit, storable as a PostgreSQL uuid, and generable on both application and server side without cross-node collision (documented).
