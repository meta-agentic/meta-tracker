---
kind: story
space: vec
id: VEC-12
title: Quarkus Native Build & Automated Kubernetes Deploy Pipeline
status: DONE
project: VEC
epic: VEC-1
sprint: VEC-S1
storyPoints: 5.0
priority: P2
labels:
- Sprint-1
- VECTIS-004
- edition-community
- tier-Core
---

## Description

As an Operations Lead, I want every code merge to automatically compile, containerize, and deploy via an automated pipeline, So that new features are securely shipped to our staging cluster within minutes without manual intervention.

**Developer Notes**
Configure a GitHub Actions workflow using GraalVM for a native Quarkus build. Package the application inside a multi-stage distroless container and generate standard K8s manifest files for deployment via ArgoCD.

---

*Backlog ref: VECTIS-004 · Type: Story · Tier: Core · Planned: Sprint 1*

## Acceptance criteria

- CI on main: a commit to main triggers a workflow that runs unit/integration tests and ArchUnit hexagonal-boundary validation; failing tests block the pipeline.
- Native image published: the workflow compiles a Quarkus native container image and pushes it to the registry, tagged with the commit/version.
- Deploy to namespace: the image auto-deploys to a target Kubernetes namespace (dev), updating the running application version.
- Health-check gate: the rollout is gated on readiness/liveness probes passing; an unhealthy rollout does not receive traffic.
- Rollback: a failed health check (or a manual trigger) rolls back to the previous image; the procedure is tested and documented.
