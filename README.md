# Vectis

**A lightweight, reactive agile development process tracking system — boards,
sprints, and real-time collaboration, built on [Quarkus](https://quarkus.io).**

Vectis is an open-source tool for planning and tracking software work: workspaces,
Kanban/Scrum boards, schema-flexible workflows, sprints, and live multi-user
collaboration — designed to stay fast as a board grows to thousands of items.

> **Status: bootstrap / planning.** This repository currently contains the
> project scaffolding, governance, and contribution harness. The application
> modules are being implemented against the roadmap below; the build, CI, and
> code-scanning workflows are enabled as the first code lands. Not yet usable as
> a running product.

## Why

Most issue trackers are either heavyweight hosted suites or simple to-do lists
that buckle under real backlogs. Vectis aims for the middle: a small,
self-hostable, reactive service that is quick to stand up and stays responsive at
scale.

- **Reactive core** — a non-blocking Quarkus backend on PostgreSQL, designed for
  high-throughput concurrent planning sessions.
- **Flexible workflows** — schema-flexible board states and transitions, so teams
  model their own process rather than fitting a fixed one.
- **Real-time** — board and item changes propagate to connected clients live.
- **High-density UI** — a virtualized client layout engine built to render large
  boards smoothly.
- **Multi-tenant by construction** — tenant-scoped data isolation as a first-class
  concern, not an afterthought.

## Architecture

Vectis is planned as a strict hexagonal architecture: a framework-free domain and
application contract at the core, with REST, persistence, and real-time adapters
at the edges, assembled by a thin Quarkus composition root. A virtualized
web client consumes the REST and streaming APIs.

The module layout and public API contract are documented here as they are
implemented.

## Editions

Vectis follows an open-core model:

- **Community edition** — this repository, licensed under **Apache-2.0**. The full
  tracking product: workspaces, boards, workflows, sprints, real-time
  collaboration, integrations, reporting, and multi-tenant security.
- **Enterprise edition** — a separately licensed, out-of-tree distribution adding
  operational tooling for regulated and air-gapped deployments (a Kubernetes
  operator, active-active high availability, zero-downtime rolling upgrades, and
  managed backup / key rotation). It is **not** part of this repository and is not
  covered by the Apache-2.0 license.

## Roadmap

Vectis is built in themed increments. Each maps to an epic in the public backlog:

- **Foundational reactive core & delivery** — the non-blocking engine, persistence
  foundation, and automated build/release pipeline.
- **Workspace topology & real-time state machine** — project spaces, flexible
  workflow transitions, and the live messaging subsystem.
- **High-density virtualized UI** — the responsive board client and layout engine.
- **Data fabric & integrations** — bulk import/export, webhooks, and automation
  hooks.
- **Cross-project portfolio** — roll-ups, multi-project validation, and team
  collaboration tooling.
- **Metrics & dashboards** — reporting engines and executive visualizations.
- **Security, identity & localization** — authentication, multi-tenant isolation,
  and UI theming / i18n.

## Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](./CONTRIBUTING.md) and
[`SECURITY.md`](./SECURITY.md) before opening a pull request. By contributing you
agree to the project's [CLA](./CLA.md).

## License

Licensed under the **Apache License 2.0** — see [`LICENSE`](./LICENSE) and
[`NOTICE`](./NOTICE).
