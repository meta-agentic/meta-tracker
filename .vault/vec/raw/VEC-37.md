---
kind: story
space: vec
id: VEC-37
title: Automated Active-Active Clustering & High Availability Topology
status: TO DO
project: VEC
epic: VEC-8
storyPoints: 8.0
priority: P2
labels:
- Enterprise-Sprint-1
- VECTIS-EE-002
- edition-enterprise
- tier-Enterprise-Infrastructure
---

## Description

As a Platform Reliability Engineer, I want the Vectis Operator to automatically orchestrate multi-node stateless application layers and active-replica database instances, So that the tracking system self-heals dynamically during an unexpected availability zone failure without data loss or UI dropouts.

**Acceptance Criteria**

* Specifying spec.replicas: 3 in the VectisCluster CRD must instantly provision separate, anti-affinity mapped pods for the Quarkus backend engine across distinct cluster hardware nodes.
* The operator must automatically spin up an enterprise-grade PostgreSQL primary instance paired with an active, hot-standby read replica utilizing automated connection pooling (e.g., PgBouncer).
* Artificially terminating the primary database pod must trigger an automated failover sequence to the replica within 15 seconds, preserving transactional integrity across the system.

**Developer Notes**
Implement native pod anti-affinity rules dynamically within the controller's reconciliation logic to guarantee compute spreading. Integrate a trusted database operator pattern (such as Crunchy Data PGO or CloudNativePG API stubs) directly into the parent Vectis controller loop to automate the stateful cluster choreography safely.

---

*Backlog ref: VECTIS-EE-002 · Type: Story · Tier: Enterprise Infrastructure · Planned: Enterprise Sprint 1*
