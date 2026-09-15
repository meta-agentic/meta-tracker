---
kind: story
space: vec
id: VEC-36
title: Secure Air-Gapped Cluster Bootstrap & Registry Mirroring
status: TO DO
project: VEC
epic: VEC-8
storyPoints: 5.0
priority: P2
labels:
- Enterprise-Sprint-1
- VECTIS-EE-001
- edition-enterprise
- tier-Enterprise-Infrastructure
---

## Description

As a SecOps Infrastructure Administrator, I want to deploy the entire Vectis system using a localized private container registry via a Kubernetes Operator, So that I can spin up our tracking environment within a strictly secure, air-gapped network with zero outbound internet access.

**Developer Notes**
Build the custom operator using the Operator SDK (Go-based controller pattern preferred for raw performance over Ansible/Helm types). Define custom resource definitions (CRDs) like VectisCluster that read local enterprise configurations. Package all static base image requirements into a single downloadable tarball package for enterprise distribution.

---

*Backlog ref: VECTIS-EE-001 · Type: Story · Tier: Enterprise Infrastructure · Planned: Enterprise Sprint 1*

## Acceptance criteria

- The Vectis Operator must successfully initialize using local image manifests, configmaps, and secrets entirely isolated from external networks (e.g., Docker Hub, GitHub Packages).
- Missing or unreachable external public endpoints must not cause the Operator reconciliation loop to crash or enter an unrecoverable back-off cycle.
- The installation process must expose custom configuration parameters to point to a localized, internal artifact mirror for license token verification.
