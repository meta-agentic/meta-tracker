# Vectis

**A lightweight, reactive agile development process tracking system — workspaces,
boards, sprints, and real-time collaboration, built on
[Quarkus](https://quarkus.io) and a virtualized web client.**

## What works today

Vectis is early, but it runs: a Quarkus server on PostgreSQL that serves its own web client and a REST API.

- **Boards.** One column per workflow state, the cards in rank order, columns that a team keeps off the board left out, and a board larger than one page of the API loaded in full.
- **Sprint.** The active sprint under a header with its goal, dates and points; a switcher for past and planned sprints.
- **Backlog.** Epics and their items as a tree, with the item count and points per epic.
- **Roadmap.** The same tree as a ledger beside a calendar grid.
- **Item detail.** Status, parent epics, story points, labels, description and dependencies, opened from any card.
- **Filter.** By text, epic, label, kind and priority; the filter is kept in the address, so it survives a reload and can be shared.
- **Workspaces.** Several workspaces on one server, switched from the top bar.
- **Preferences.** English or Italian; light, dark or system theme.
- **REST API under `/api/v1`.** Read workspaces, boards, sprints, items, templates and a workspace's configuration; create, edit, move and delete items, each change checked against the item's version (`If-Match`).
- **Import.** A server can read a backlog kept as Markdown files with YAML front matter and show it as a workspace (see below).

Not there yet: sign-in, so do not expose a Vectis server to a network you do not trust; editing in the browser, which is read-only today (items change through the API); live updates between open browsers.

## Run it

You need Docker, JDK 25, Maven and Node.js. From the repository root:

```sh
# A throwaway PostgreSQL
docker run -d --name vectis-demo-db -e POSTGRES_USER=vectis -e POSTGRES_PASSWORD=vectis \
  -e POSTGRES_DB=vectis -p 127.0.0.1:5432:5432 postgres:18-alpine

# The web client, opening the demo workspace by default, then the server that serves it
(cd web && npm ci && VITE_VECTIS_WORKSPACE_KEY=E2E npm run build)
mvn -B -ntp -pl vectis-server -am package -DskipTests

# Start the server: it creates the schema at start and listens on port 8080
VECTIS_DB_PASSWORD=vectis java -jar vectis-server/target/quarkus-app/quarkus-run.jar
```

Once the server is up, load the demo data from a second terminal:

```sh
for f in boards filter workspaces; do
  docker exec -i vectis-demo-db psql -q -v ON_ERROR_STOP=1 -U vectis -d vectis < e2e/seed/$f.sql
done
```

Open <http://localhost:8080>. The demo has two workspaces: `E2E`, with one board, three sprints, two epics and a little over two hundred items, and `TWO`, a small second workspace. These are the data the browser tests in `e2e/` run against; loading them again replaces them.

Ctrl-C stops the server. `docker rm -f vectis-demo-db` removes the database. `QUARKUS_HTTP_PORT` moves the server, and `VECTIS_DB_HOST`, `VECTIS_DB_PORT`, `VECTIS_DB_NAME` and `VECTIS_DB_USER` point it at another database.

### Import a Markdown backlog

The server can also read a backlog kept as Markdown files with YAML front matter: one file per item in a folder per status tier, and the sprints in a folder of their own. The vault connector's test data is a small example. To import it, start the server with its location and run the import:

```sh
VECTIS_DB_PASSWORD=vectis \
VECTIS_IMPORT_VAULT_ROOT="$PWD/vectis-connector-vault/src/test/resources/vaults/mapping/vault" \
VECTIS_IMPORT_VAULT_SPACE=demo \
  java -jar vectis-server/target/quarkus-app/quarkus-run.jar

curl -X POST http://localhost:8080/api/v1/imports/vault
```

The import fills a workspace named after the items' key prefix (`DEMO` here) and answers with what it created, updated and left unchanged, and with every file it could not map cleanly; that example is built to carry a few.

- A request never names a path: the server reads only the directory it was started with. Without one, the endpoint answers 404.
- The import only reads. Running it again changes nothing unless a file changed, and an edited file updates that item and no other. An item deleted from the files is not deleted from the board.
- The files stay the system of record: in an imported workspace every item write through the API answers 409, and the workspace reports `"readOnly": true`.
- It has been run on the JVM only, not in the native executable.

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
[ADR-VEC-01 — product requirements & feature model](./docs/adr/ADR-VEC-01-product-requirements-and-features.md).

### Principles

The principles below are the design Vectis is built towards; [What works today](#what-works-today) says how far it has come.

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

### Architecture

A hexagon of Maven modules, a runnable composition root and a separate web client:

| Module | Role |
|--------|------|
| `vectis-domain` | The domain model: workspaces, boards, columns, items, sprints, and time-ordered identity. No framework imports. |
| `vectis-extension-spi` | The stable, framework-free contract that plugins and backlog sources implement. |
| `vectis-core` | Extension discovery and the community fallback implementations. |
| `vectis-persistence` | Schema, migrations and non-blocking repositories on the reactive PostgreSQL client. |
| `vectis-command` | The one command layer every channel changes state through, free of JAX-RS. |
| `vectis-connector-vault` | Reads a backlog kept as Markdown files into the import. |
| `vectis-plugin-audit-enterprise` | An example of a separately packaged plugin, kept here to prove runtime plugin loading. |
| `vectis-server` | The runnable Quarkus application: REST resources, the import, and the web client on the API's origin. |
| `web/` | The single-page client (React with a cache-layered store), built into `vectis-server`. |

The domain and the extension contract carry no framework dependency, and a build-time test fails any server class that writes to the database without going through the command layer.

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
