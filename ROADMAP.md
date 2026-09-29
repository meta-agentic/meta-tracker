# Vectis Roadmap

This is the public roadmap for the **Vectis Community edition**. It is maintained in the open for full visibility into where the project is heading, and the full backlog behind it ships with the repository under [`.vault/`](./.vault/README.md).

It mirrors our internal planning and is intentionally directional, not a dated
commitment: scope and ordering can change as we learn. The roadmap covers the
open-source Community edition only — the separately licensed
[Enterprise edition](./README.md#editions) is developed out of tree and is not
part of this roadmap.

> **Status: M1 mostly delivered** (as of 2026-09-29). The reactive core, time-ordered identifiers, native build and delivery, the extension model, the first persistence slice, continuous integration and the web client's API client are in. Template provisioning is next: its template-to-instance model spike is in progress. Live re-tagging and the multi-tenant blueprint are still planned. The backlog in `.vault/` is the item-level view, and this file is the narrative summary.

**Legend:** ⬜ planned · 🟡 in progress · ✅ done

---

## M1 · Foundations — reactive core & delivery

The non-blocking engine and the path to ship it.

- ✅ High-throughput, lock-free **time-ordered identifiers** for items (no write hotspots or index fragmentation under heavy concurrent planning)
- ⬜ **Project & template provisioning** — create a workspace from a Kanban or Scrum template, with columns and item types laid out automatically (the template-to-instance model spike is in progress)
- ⬜ **Live project re-tagging** — change a project's display key without breaking
  existing references
- ✅ **Native build + automated container/Kubernetes delivery** pipeline
- ✅ **Core persistence slice** — workspace, board, column and item model with its schema, migrations and reactive repository
- ✅ The web client has a **typed API client** in place of its synthetic data generator, with cached first paint and explicit loading, stale and error states; the workspace endpoints it reads arrive with template provisioning
- ✅ **Continuous integration** builds and tests both the server and the web client

*De-risking spikes up front:* the extension/plugin-loading model (✅ done), and the multi-tenant isolation & feature-gating blueprint (⬜ planned).

## M2 · Workspace topology & real-time state machine

Make the board live and the workflow the team's own.

- ✅ **Sprint mechanics** in the domain model and persistence — create, start and complete a sprint, moving items between backlog and sprint (no API or UI yet)
- ⬜ **Schema-flexible workflow engine** with transition guardrails (cards can't
  skip required states), enforced on the server, not just the UI
- ⬜ **High-velocity inline task grid** — keyboard-first create/edit/delete
- ⬜ **Real-time synchronization** over Server-Sent Events, so every open board
  reflects changes within moments

## M3 · Data fabric & integrations

Get work in and out, and open the seams.

- ⬜ **Streaming import** of large backlogs without timeouts or memory spikes
- ⬜ **Bidirectional mapping/export** against external trackers
- 🟡 **Tracker adapter contract** — one read-only contract any backlog can be imported through, with this repository's own `.vault/` backlog as the first source (design done, read side in progress)
- ✅ **Client store optimization** with cache layering for instant board switching
- ⬜ **Model Context Protocol (MCP) endpoint** so AI agents can safely read and
  update the backlog through the same rules as the UI

## M4 · High-density experience

Stay smooth at thousands of items.

- ✅ **Dual-axis virtualization** profiling for large, long-range timelines
- 🟡 A **design pass on the web client** — app shell, board cards, item detail, and designed loading, empty and error states
- ⬜ **Expandable hierarchical backlog tree** (initiatives → epics → stories)
- ⬜ **Synchronized dual-pane ledger + Gantt roadmap canvas**

## M5 · Portfolio, collaboration, security & identity

Scale across teams, and let people in safely.

- ⬜ **Cross-project initiative roll-ups** with combined progress
- ⬜ **Inter-team dependency gatekeeper** (block "done" on unresolved cross-team
  prerequisites)
- ⬜ **Real-time collaborative estimation** (planning poker)
- ⬜ **Multi-tenant OIDC** with federated identity providers
- ⬜ **Client-managed authentication** (Authorization Code + PKCE) with the backend
  as a pure resource server, isolating data by tenant claims
- ⬜ **Per-user preferences**
- ✅ An extensible **localization & theming** framework for the web client

## M6 · Insights & dashboards

Turn the flow into signal.

- ⬜ **Cumulative Flow Diagram** for bottleneck and WIP analysis
- ⬜ **Velocity & sprint burndown**
- ⬜ **Cycle-time / lead-time percentile histograms**
- ⬜ **Executive multi-project portfolio health dashboard**

---

## Enterprise edition

Operational tooling for regulated and air-gapped deployments (a Kubernetes
operator, high availability, zero-downtime upgrades, managed backup and key
rotation) ships in the separately licensed Enterprise edition. It is out of scope
for this Community roadmap; see [Editions](./README.md#editions).

## How this roadmap is maintained

- The backlog in [`.vault/`](./.vault/README.md) is the item-level view — one file per item, its directory is its status; this file is the human-readable summary, updated as milestones progress.
- Have a request or a strong opinion on ordering? Open a
  [feature request](./.github/ISSUE_TEMPLATE/feature_request.yml) or start a
  discussion — community input genuinely shapes this list.
