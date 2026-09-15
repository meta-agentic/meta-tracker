---
kind: story
space: vec
id: VEC-38
title: Non-Breaking Rolling Upgrades for Schema-less Schema States
status: TO DO
project: VEC
epic: VEC-8
storyPoints: 8.0
priority: P2
labels:
- Enterprise-Sprint-2
- VECTIS-EE-003
- edition-enterprise
- tier-Enterprise-Infrastructure
---

## Description

As a System Operations Lead, I want the Operator to safely orchestrate a progressive rolling upgrade across our application and database layers when applying enterprise LTS version upgrades, So that developers can continue logging and moving cards without experiencing a single millisecond of service downtime.

**Acceptance Criteria**

* Updating the spec.version tag in the custom resource must trigger a canary or blue-green rollout structure managed natively by the operator logic.
* The operator must execute database pre-migration validation steps before routing traffic to the updated Quarkus container nodes.
* If a container initialization error or a structural conflict occurs during a version bump, the operator must halt the rollout automatically and revert to the last stable container snapshot safely.

**Developer Notes**
Because Vectis utilizes a dual database architecture (structured relational definitions alongside volatile JSONB configuration payloads), upgrades must use a backward-compatible two-phase execution strategy. Ensure the Quarkus backend handles a temporary dual-schema operational window gracefully, allowing the operator to cycle nodes out sequentially without dropping connection streams.

---

*Backlog ref: VECTIS-EE-003 · Type: Story · Tier: Enterprise Infrastructure · Planned: Enterprise Sprint 2*
