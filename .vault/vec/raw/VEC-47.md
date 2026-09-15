---
kind: epic
space: vec
id: VEC-47
title: Tracker Adapter Architecture — one contract, many backlogs, the vault as reference adapter
status: TO DO
project: VEC
priority: P0
labels:
- VECTIS-E09
- edition-community
- tier-Extensibility-Data
- adapters
- vault
- meta-os
- visibility
---

## Description

Give Vectis one adapter contract that any backlog can be read and written through, and
implement the estate's own three-layer memory system (the vault) as the reference adapter,
so that pointing Vectis at a meta-os instance wires up every backlog that instance knows
about — without a per-source integration each time.

## Why this exists

Since the estate left Jira (ADR-MOS-06 made the vault the sole tracker), **there is no way
to see the work.** The backlog is ~46 items in `vec/`, several hundred in `ios/`, spread over
`raw/` · `wiki/` · `output/` tiers across many spaces, readable only by grepping markdown or
running `backlog.py query`. The product owner's own words, 2026-09-04:

> "substantially since we left JIRA I am blind like driving a submarine: I have sensors but
> my sight is very limited in perception of every detail and also as overview"

That is a P0 operational problem, not a nice-to-have. Decisions are being made without an
overview, and overnight agent work is invisible except through a meta-dashboard that shows
process, not backlog state.

Vectis is the intended cure, and it is closer than the README suggests — but see
**Current reality** below: the SPA renders synthetic data and the server exposes no
work-item API, so today Vectis cannot show a single real item.

## Current reality (verified in code, 2026-09-04 — the README's "bootstrap/planning" understates it)

* `vectis-extension-spi/.../SyncConnector.java` **already exists** and its javadoc already
  describes this epic — *"a bidirectional bridge between a Vectis workspace and an external
  tracker (Jira, GitHub Projects, a customer's in-house system)"*. **But it is a stub:** the
  interface declares only `id()` and `available()`. There is no read, no write, no mapping,
  no conflict story. This epic's job is to give that seam a real contract, not to invent one.
* Discovery already works — `ExtensionRegistry` + `ExtensionDiagnosticsResource`
  (`GET /api/extensions`) is the **only** REST resource in the whole server.
* The domain is modelled: `Workspace`, `Board`, `BoardColumn`, `Item`, `Sprint`,
  `SprintStatus`, `TimeOrderedId` (7 types in `vectis-domain`).
* The SPA (`web/`) renders boards and a virtualized timeline, and does it well — but from
  `generateWorkspace({ boards: 5, epics: 18, issues: 4_000, years: 3 })`. There is **no
  `fetch`, no `EventSource`, no API module anywhere in `web/src`.** The synthetic ids even
  collide with real ones: the board shows a card "VEC-17 · Task 17" while the real VEC-17 is
  *"Server-Sent Events (SSE) Workspace Synchronization"*.

So the pieces exist and none of them are joined.

## Shape of the solution

1. **A real `SyncConnector` contract.** Read a source's items into the Vectis domain model,
   and write changes back. Decide explicitly: pull vs push vs bidirectional; what identity
   means across systems; what happens on conflict; whether sync is snapshot or incremental.
   Ports-and-adapters — the contract lives in `vectis-extension-spi`, adapters live outside
   the core, discovered at runtime the way `AuditLogger` implementations already are.
2. **The vault as the reference adapter.** The three-layer memory system is the first real
   implementation, and the one that proves the contract is honest. Non-negotiable: the vault
   keeps its own invariants — **the folder IS the lifecycle state**, `status:` is never
   hand-edited, ids are never hand-numbered, transitions go through `backlog.py transition`.
   A Vectis adapter that writes back MUST go through that CLI or reimplement it exactly,
   including the tier move and the sprint stamp as one atomic step. Getting a subset right is
   the documented way agents corrupt the vault.
3. **Meta-os instance binding.** Point Vectis at a meta-os instance (e.g. `mova-os`) and it
   discovers every backlog that instance governs — every vault space, and in principle any
   other source that instance has an adapter for. One binding, not one integration per space.
4. **Read-only first.** A correct read-only view of the real backlog already solves the
   stated problem. Write-back is a second increment and carries all the risk.

## Relationship to existing work

This epic **owns the contract**; existing items become adapters or consumers of it, and
should be re-homed under it once the design lands rather than being promoted now:

* **VEC-19** Bidirectional Jira Schema Mapper & Export Interface — becomes an adapter.
* **VEC-26** MCP Server Backlog Sync Tool — becomes an adapter (and the agent-facing surface).
* **VEC-18** Streaming Stateless JSON Import Utility — a degenerate one-shot adapter.
* **VEC-4** Enterprise Data Fabric & Semantic Integrations — the existing integrations epic.
  VEC-47 is deliberately narrower and architectural: VEC-4 is about bulk/chunked processing
  and protocol implementations, VEC-47 is about one contract that makes any tracker a source.
  If, on design, they collapse into one, prefer merging into VEC-4 over keeping both.

## The visibility path is a separate spine — do not confuse them

Even a perfect adapter shows nothing until the SPA talks to a server. That chain is
**VEC-45 → VEC-10 → VEC-42** (template/instance model → endpoint contract → real API client
replacing the generator). VEC-42 is REFINED and explicitly *not pullable* until VEC-10 lands.
Both spines must land for the product owner to see real work; neither alone is sufficient.

## Open questions for refinement

* Does the adapter read the vault's **markdown files** directly, or shell out to `backlog.py`,
  or does `backlog.py` grow a machine-readable export? (`backlog.py export` already exists —
  check what it emits before designing anything.)
* Is Vectis the system of record, or a projection? ADR-MOS-06 says the vault is the tracker.
  If Vectis is a **projection**, write-back is a much smaller problem and this epic gets
  cheaper. **Decide this first — it changes everything downstream.**
* How are ids reconciled when two sources both use `VEC-17`?
* Does a meta-os binding imply live watching (filesystem/SSE) or periodic reconciliation?

## Decompose before committing

This is complex **and** broad — do not commit it unsplit. At minimum: the contract design
spike, the vault read adapter, the meta-os binding, and write-back are separate items.

---

*Backlog ref: VECTIS-E09 · Tier: Extensibility & Data*
