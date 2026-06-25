# Vectis Roadmap

This is the public roadmap for the **Vectis Community edition**. It is maintained
in the open for full visibility into where the project is heading, and is tracked
on the repository's **GitHub Project board** (see the *Projects* tab).

It mirrors our internal planning and is intentionally directional, not a dated
commitment: scope and ordering can change as we learn. The roadmap covers the
open-source Community edition only — the separately licensed
[Enterprise edition](./README.md#editions) is developed out of tree and is not
part of this roadmap.

> **Status: bootstrap.** Everything below is **planned**. As work begins, items
> move to *in progress* and then *done*; the GitHub Project board is the live
> view, and this file is the narrative summary.

**Legend:** ⬜ planned · 🟡 in progress · ✅ done

---

## M1 · Foundations — reactive core & delivery

The non-blocking engine and the path to ship it.

- ⬜ High-throughput, lock-free **time-ordered identifiers** for items (no write
  hotspots or index fragmentation under heavy concurrent planning)
- ⬜ **Project & template provisioning** — create a workspace from a Kanban or
  Scrum template, with columns and item types laid out automatically
- ⬜ **Live project re-tagging** — change a project's display key without breaking
  existing references
- ⬜ **Native build + automated container/Kubernetes delivery** pipeline

*De-risking spikes up front:* the extension/plugin-loading model, and the
multi-tenant isolation & feature-gating blueprint.

## M2 · Workspace topology & real-time state machine

Make the board live and the workflow the team's own.

- ⬜ **Schema-flexible workflow engine** with transition guardrails (cards can't
  skip required states), enforced on the server, not just the UI
- ⬜ **High-velocity inline task grid** — keyboard-first create/edit/delete
- ⬜ **Real-time synchronization** over Server-Sent Events, so every open board
  reflects changes within moments

## M3 · Data fabric & integrations

Get work in and out, and open the seams.

- ⬜ **Streaming import** of large backlogs without timeouts or memory spikes
- ⬜ **Bidirectional mapping/export** against external trackers
- ⬜ **Client store optimization** with cache layering for instant board switching
- ⬜ **Model Context Protocol (MCP) endpoint** so AI agents can safely read and
  update the backlog through the same rules as the UI

## M4 · High-density experience

Stay smooth at thousands of items.

- ⬜ **Dual-axis virtualization** profiling for large, long-range timelines
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
- ⬜ **Per-user preferences**, plus an extensible **localization & theming** framework

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

- The **GitHub Project board** is the live status view; this file is the
  human-readable summary, updated as milestones progress.
- Planning is curated from our internal tracker and mirrored here in the open.
- Have a request or a strong opinion on ordering? Open a
  [feature request](./.github/ISSUE_TEMPLATE/feature_request.yml) or start a
  discussion — community input genuinely shapes this list.
