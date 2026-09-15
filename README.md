# Vectis

**A lightweight, reactive agile development process tracking system — workspaces,
boards, sprints, and real-time collaboration, built on
[Quarkus](https://quarkus.io) and a virtualized web client.**

## The name

**Vectis** is the Latin word for a **lever** — the bar you set against a fulcrum
to move a load far heavier than you could by hand. It descends from *vehere*,
"to carry, to convey, to move forward."

That is exactly what a good tracker is: not brute force applied to a backlog, but
a small, well-placed instrument that lets a team move a large body of work with
*proportionate* effort. Leverage, not labour — *"give me a place to stand and I
will move the Earth."* The *vehere* root carries the second half of the idea: a
board's whole job is to **convey work forward**, from intent to done. Vectis is
built to be that lever: light in the hand, heavy in what it moves.

> **Status: bootstrap / planning.** This repository currently holds the project
> scaffolding, governance, and contribution harness. The application modules
> described below are being implemented against the roadmap; the build, CI, and
> code-scanning workflows activate as the first code lands. Not yet usable as a
> running product.

## Why

Most issue trackers sit at one of two extremes: heavyweight hosted suites that are
slow to stand up and resist self-hosting, or simple to-do lists that buckle the
moment a real backlog grows past a few hundred items. Vectis aims for the
underserved middle — a small, self-hostable, **reactive** service that is quick to
deploy and *stays responsive at scale*, while letting teams model their own
process rather than bending to a fixed one.

Design goals, in priority order:

1. **Stay fast as the board grows.** Thousands of items, many concurrent editors,
   no lock contention, no UI jank.
2. **Bend to the team's process**, not the other way around — workflows are data,
   not hard-coded columns.
3. **Self-host without ceremony** — a single reactive service, native-image
   friendly, with a clean Kubernetes path.
4. **Be safe by construction** — multi-tenant isolation that fails *closed*, not
   open.

## Design

Product requirements and architectural decisions are recorded as ADRs under
[`docs/adr/`](./docs/adr/) — start with
[ADR-01 — product requirements & feature model](./docs/adr/ADR-01-product-requirements-and-features.md).

### Principles

- **Reactive, non-blocking core.** Every request path is asynchronous end to end
  (Quarkus + Mutiny over a reactive PostgreSQL client), so throughput is bounded
  by work, not by threads parked on I/O.
- **Lock-free, time-ordered identity.** Primary keys are generated application-side
  as time-ordered identifiers (UUIDv7 / ULID), giving natural chronological sort
  and index locality without the write-hotspot and fragmentation of a central
  sequence — so massive concurrent planning sessions don't serialize on the
  database.
- **Workflows are data.** Board states and the transitions between them are a
  schema-flexible model (relational structure alongside JSONB payloads), so a team
  defines its own columns, statuses, and transition guardrails at runtime instead
  of waiting on a schema migration.
- **Real-time by default.** State changes fan out to connected clients over
  Server-Sent Events, keeping every open board eventually-consistent within
  milliseconds.
- **High-density client.** The web client is a virtualized, dual-axis layout engine
  with a cache-layered local store, built to render very large boards and timelines
  smoothly rather than paginating them away.
- **Multi-tenant, fail-closed.** Tenancy is a first-class concern: a request's
  tenant is resolved once at the edge and carried in a request-scoped context;
  every tenant-scoped table is guarded by PostgreSQL row-level security, so a
  missing tenant binding makes rows *invisible* rather than leaking them.
- **Extensible at the seams.** A decoupled extension interface, a streaming
  import/export fabric, webhooks, and a Model Context Protocol surface let Vectis
  exchange work with other systems — including AI planning agents — without
  bloating the core.
- **Strict hexagonal architecture.** A framework-free domain and application
  contract at the centre; REST, persistence, and real-time concerns kept at the
  edges; only the composition root knows the concrete adapters.

### Architecture (planned)

Vectis is structured as a four-module hexagon plus a thin composition root and a
separate web client:

| Module | Role |
|--------|------|
| `vectis-domain` | Pure-Java domain model: workspaces, boards, items, workflows, sprints. No framework imports. |
| `vectis-api` | Application contract: inbound use cases + outbound ports. Depends on the domain only. |
| `vectis-persistence` | Outbound adapter: reactive PostgreSQL (Hibernate Reactive / reactive pg-client); migrations own the schema and row-level security. |
| `vectis-rest` | Inbound adapter: reactive REST resources, real-time (SSE) endpoints, OpenAPI. |
| `vectis-server` | Composition root — the runnable Quarkus application. |
| `web/` | The virtualized single-page client (React + a cache-layered store). |

The `domain` and `api` modules are framework-free by construction; architectural
boundaries are enforced by tests so they cannot erode silently.

### Technology

- **Backend:** Java + Quarkus (reactive / Mutiny), reactive PostgreSQL, JSONB for
  flexible workflow payloads, native-image friendly.
- **Real-time:** Server-Sent Events for workspace synchronization.
- **Client:** A virtualized single-page app with a cache-layered global store, a
  hierarchical backlog explorer, and a synchronized ledger + Gantt roadmap canvas.
- **Integration:** Streaming JSON import, bidirectional mapping/export against
  external trackers, and a Model Context Protocol server for AI-assisted planning.
- **Delivery:** Native build with an automated container / Kubernetes pipeline.

## How it will be implemented

Vectis is built in themed increments. The full, living roadmap — with current
status — is in [`ROADMAP.md`](./ROADMAP.md) and on the project's **GitHub Project
board** (the *Projects* tab). Each increment is independently shippable, and the
order is deliberately bottom-up: prove the engine, then the workflow, then the
experience, then everything that rides on top.

1. **Foundations — reactive core & delivery.** Validate time-ordered key
   throughput, then stand up the reactive engine, project/template provisioning,
   live project rebranding, and a native build + Kubernetes deploy pipeline.
2. **Workspace topology & real-time state machine.** The schema-flexible workflow
   engine with transition guardrails, a high-velocity inline task grid, and
   SSE-based live synchronization across collaborators.
3. **Data fabric & integrations.** Streaming import, bidirectional external-tracker
   mapping/export, a Model Context Protocol sync surface, and the decoupled plugin
   architecture that later extensions hang off.
4. **High-density experience.** Dual-axis virtualization profiling, an expandable
   hierarchical backlog tree, and the synchronized dual-pane ledger + Gantt roadmap
   canvas — the parts that have to stay smooth at thousands of items.
5. **Cross-project portfolio.** Strategic initiative roll-ups, an inter-team
   dependency gatekeeper, and real-time collaborative estimation.
6. **Security, identity & localization.** Multi-tenant OIDC resolution, federated
   authentication, per-user preferences, and an extensible localization + theming
   framework — on the multi-tenant isolation and feature-gating foundations proven
   early as architecture spikes.
7. **Metrics & dashboards.** Cumulative flow, velocity and burndown, cycle/lead-time
   percentile histograms, and an executive multi-project portfolio health view.

Two architecture spikes run ahead of the build to de-risk the cross-cutting
decisions — the **extension/plugin loading model** and the **multi-tenant
isolation & feature-gating blueprints** — so the seams are settled before the
features that depend on them are written.

## Editions

Vectis follows an open-core model:

- **Community edition** — this repository, licensed under **Apache-2.0**. The full
  tracking product: workspaces, boards, schema-flexible workflows, sprints,
  real-time collaboration, integrations, reporting, and multi-tenant security.
- **Enterprise edition** — a separately licensed, out-of-tree distribution adding
  operational tooling for regulated and air-gapped deployments (a Kubernetes
  operator, active-active high availability, zero-downtime rolling upgrades, and
  managed backup / key rotation). It is **not** part of this repository and is not
  covered by the Apache-2.0 license.

## Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](./CONTRIBUTING.md) and
[`SECURITY.md`](./SECURITY.md) before opening a pull request. By contributing you
agree to the project's [CLA](./CLA.md).

## License

Licensed under the **Apache License 2.0** — see [`LICENSE`](./LICENSE) and
[`NOTICE`](./NOTICE).
