# ADR-VEC-01 — Vectis product requirements & feature model

| | |
|---|---|
| **Status** | Accepted (2026-10-26, by the PO). Requirements and their numbers are unchanged since 2026-07-04; the [status update](#status-update-2026-10-26) records what later decisions and code have realised |
| **Date** | 2026-07-04 |
| **Deciders** | Product Owner |
| **Scope** | Community edition requirements & feature model; interface and reporting direction |

## Context

Vectis's design intent lives in [`README.md`](../../README.md) (principles,
hexagonal module plan, technology) and [`ROADMAP.md`](../../ROADMAP.md)
(M1–M6). What has been missing is a **single decision record that states the
product's requirements** — testable, numbered, and citable — so that backlog
items derive their acceptance criteria from one source instead of inventing
thresholds story by story.

For the **interface** and especially the **reporting** surface, we take
deliberate conceptual inspiration from JetBrains **YouTrack**, whose
query-driven, keyboard-first interaction model and composable reporting are —
in our view — the strongest in the tracker category. The inspiration is
*clean-room and conceptual only*: we adopt ideas (a structured query language,
command-driven bulk editing, reports as query + grouping + visualization,
widget dashboards), not YouTrack's code, assets, or visual design. YouTrack is
a trademark of JetBrains s.r.o.; Vectis is unaffiliated.

## Decision

Vectis commits to the requirement set below. Requirements are numbered
(`R-<area>-<n>`) and grouped; each maps to a roadmap milestone. Open decisions
(`OD<n>`) capture what needs a spike or a follow-on ADR before implementation.

Performance numbers marked *(target)* are engineering targets to be validated
or revised by the referenced spikes — stories cite them from here rather than
restating their own.

### 1 · Core engine (M1–M2)

- **R-CORE-1** Every request path is reactive and non-blocking end to end;
  throughput is bounded by work, not thread parking.
- **R-CORE-2** Item identity is application-generated, lock-free, and
  time-ordered (UUIDv7/ULID family). *(target)* sustain 100,000 concurrent
  write requests in the identity spike without index fragmentation.
- **R-CORE-3** Workflows are data: board states, transitions, and transition
  guardrails are defined at runtime (relational + JSONB), enforced
  server-side — never only in the UI.
- **R-CORE-4** State changes propagate to all connected clients over SSE.
  *(target)* visible on every open board within 500 ms; remote updates are
  visually highlighted.
- **R-CORE-5** A workspace is provisioned from a **template** (Kanban, Scrum)
  and lives on as a **custom instance**: the instance references its template
  and carries only its overrides (states, item types, fields, policies).
  Template evolution is inheritable; instance deltas are explicit and
  reviewable. *(This template → instance model is a core Vectis concept, not
  an implementation detail.)*
- **R-CORE-6** Projects can be re-keyed/rebranded live without breaking
  existing references.

### 2 · Interface (M2, M4–M5) — YouTrack-inspired

- **R-UX-1 Keyboard-first.** Every frequent action (create, edit, transition,
  assign, tag) is reachable without the mouse; the task grid supports
  inline editing at typing speed.
- **R-UX-2 Command dialog.** A single command surface applies structured
  commands to the current selection — e.g. `state Done tag perf assignee me` —
  with autocomplete over the workspace's own fields and values. Commands are
  batched server-side (one round trip per bulk apply) and guardrail-checked
  like any other transition.
- **R-UX-3 Structured query language.** One human-writable query grammar with
  as-you-type completion over fields, values, and links; used *everywhere* a
  set of items is needed: board filters, tree views, reports, dashboards, API.
  Queries are URL-shareable. **Saved searches** are first-class, named,
  permission-scoped entities.
- **R-UX-4 High-density virtualization.** Large boards, trees, and timelines
  render without pagination. *(target)* 60 fps continuous scroll at 2,500
  items × 365-day dual-axis layout (validated by the virtualization spike).
- **R-UX-5 Instant navigation.** Board switching renders cached local data
  immediately and revalidates in the background; item-level edits re-render
  locally, not per-screen.
- **R-UX-6 Dashboards.** Personal and shared dashboards composed of
  **widgets** (report widget, query/issue-list widget, markdown note widget).
  Widgets are an extension point (see OD3) so plugins can contribute new ones.
- **R-UX-7 Theming & localization.** Extensible locale + theme provider;
  light/dark by default.

### 3 · Reporting & analytics (M6) — YouTrack-inspired, the flagship surface

- **R-REP-1 Report = query + grouping + visualization.** Every report is
  defined by (a) a query (R-UX-3) selecting its item set, (b) one or two
  grouping fields, (c) a visualization. No report is a hard-coded page over a
  fixed dataset.
- **R-REP-2 Distribution reports.** Flat (one field → pie/bar/table) and
  **two-dimensional matrix** (two fields → grid with row/column totals) over
  any field, including custom/instance-defined fields.
- **R-REP-3 Flow & time-management reports.** Sprint burndown (scope-change
  aware), historical velocity, cumulative flow diagram with WIP/bottleneck
  inefficiency detection, and cycle-/lead-time percentile histograms
  (p50/p85/p95).
- **R-REP-4 Portfolio roll-up.** Cross-project initiative roll-ups and an
  executive multi-project health dashboard built from the same report
  primitives (R-REP-1), not a parallel system.
- **R-REP-5 Sharing & lifecycle.** Reports and dashboards carry an owner and
  a visibility scope (private / team / tenant); recalculation is on-demand
  with visible freshness, and schedulable. Sharing respects tenant isolation
  (fail-closed, R-SEC-2).
- **R-REP-6 Export & API parity.** Any report's underlying data exports as
  CSV/JSON, and is retrievable through the same API the UI uses — agents (MCP,
  R-INT-3) read reports through the same rules as humans.
- **R-REP-7 Performance.** *(target)* any distribution report over 100k items
  returns in under 3 s server-side; dashboards load from cached aggregates
  with staleness indicated (strategy = OD2).

### 4 · Data fabric & extensibility (M3)

- **R-INT-1** Streaming import of very large backlogs with progress feedback
  and bounded memory.
- **R-INT-2** Bidirectional field mapping/export against external trackers.
- **R-INT-3** An MCP surface through which AI agents read and update the
  backlog **through the same guardrails and permissions as the UI**.
- **R-INT-4** A decoupled plugin/extension interface at the seams (widgets,
  importers, report types); Community-edition dependencies remain
  Apache-2.0-compatible.

### 5 · Security & identity (M5)

- **R-SEC-1** Multi-tenant OIDC with federated IdPs; client-managed
  Authorization Code + PKCE; the backend is a pure resource server.
- **R-SEC-2** Tenant isolation fails **closed** (PostgreSQL row-level
  security; missing tenant binding ⇒ rows invisible). Applies equally to
  reports, saved searches, and dashboards.

### 6 · Editions

Community edition (this repository) carries everything above. Enterprise
operational tooling is out of tree and out of scope here — see
[Editions](../../README.md#editions).

## Status update (2026-10-26)

Accepted as written: the requirement numbers are stable and backlog items keep citing them. This section records what has happened since the record was proposed. It changes no requirement; where a later accepted ADR supersedes a mechanism clause, the later ADR says so.

### What realises the requirements today

| Requirement | Realised by | State |
|---|---|---|
| R-CORE-1 reactive end to end | Reactive persistence slice (VEC-41); readiness on the reactive pool (VEC-101) | In code |
| R-CORE-2 time-ordered identity | Time-ordered primary-key performance matrix (VEC-9) | Evaluated; the 100,000-write target is not yet re-measured on the persistence slice |
| R-CORE-3 workflows are data, enforced server-side | [ADR-VEC-03](./ADR-VEC-03-workflow-and-template-model.md) (Accepted); the template resolver, hierarchy, locks, delivery gates and write path in `vectis-domain` (VEC-90, VEC-91, VEC-92) | Domain in code; persistence of templates (VEC-68) is next |
| R-CORE-4 SSE propagation | Real-time transport spike (VEC-46) and the event write path (VEC-73) | Write path in code; transport decision record still Proposed |
| R-CORE-5 template to instance | ADR-VEC-03 (the N-level hierarchy generalises the two-level model) and the template-instance spike (VEC-45) | Decided and in the domain model; workspaces reference a template once VEC-68 lands |
| R-UX-4, R-UX-5 virtualization, instant navigation | Dual-axis profiling (VEC-21), store layering (VEC-20), tree explorer (VEC-22), ledger and roadmap canvas (VEC-23) | Spikes and first screens delivered |
| R-UX-7 theming and localization | Localization and theme provider framework (VEC-34) | Delivered |
| R-INT-1 streaming import | Vault import, read side (VEC-57) | Partial: read side only |
| R-INT-2, R-INT-3 mapping and agent surface | [ADR-VEC-04](./ADR-VEC-04-integration-contract-and-agent-surface.md) (Accepted 2026-10-22): the tracker adapter contract, write-back through the format's writer, the agent surface under the same guardrails | Decided; adapters not yet built |
| R-INT-4 extension interface | Plugin-loading architecture (VEC-13), [`docs/spikes/plugin-loading.md`](../spikes/plugin-loading.md) | Decided in the spike; no plugin shipped yet |
| R-SEC-1 OIDC, PKCE | [ADR-VEC-02](./ADR-VEC-02-identity-and-auth.md) (Proposed): a browser session through the backend replaces the "client-managed, pure resource server" mechanism; the intent is unchanged | Decision pending acceptance |
| R-SEC-2 fail-closed tenant isolation | Tenant-isolation blueprint (VEC-14); row-level security policies are a separate item (VEC-80) | Blueprint only; not yet enforced in the schema |
| R-UX-1, R-UX-2, R-UX-3, R-UX-6, R-REP-1 to R-REP-7, R-CORE-6 | Nothing yet | Not started |

### Open decisions, as of today

| # | Status |
|---|---|
| **OD1** query language | Open. Needs its spike and follow-on ADR. |
| **OD2** report computation model | Open. Needs its spike and follow-on ADR. |
| **OD3** widget and plugin coupling | Resolved in principle by the plugin-loading spike (VEC-13). The widget contract is specified when the first dashboard widget is built. |
| **OD4** time tracking in or out of Community scope | Open. A PO decision. |
| **OD5** saved-search, report and dashboard permission model | Folded into ADR-VEC-02, as planned. Open until that ADR is accepted. |

### Follow-on ADRs: what exists

Workflow and template model: ADR-VEC-03, Accepted. Integration and agent surface: ADR-VEC-04, Accepted. Identity and auth: ADR-VEC-02, Proposed. Still to be written: real-time transport (spike VEC-46), client store and virtualization, extension loading, multi-tenant isolation and feature gating, query language (OD1), reporting engine (OD2).

### Deployment

The requirements do not fix a deployment target. The Community edition runs as a container; for now the project's own instance is planned on Cloud Run (VEC-102), and the Kubernetes manifests under `deploy/` stay as reference material for self-hosters until the cost and need of a cluster are assessed (VEC-103).

## Open decisions

| # | Question | Resolution path |
|---|----------|-----------------|
| **OD1** | Query language grammar & evaluation: own grammar vs adapting an existing style; parsing/completion architecture; safe server-side evaluation over relational + JSONB fields | Spike + follow-on ADR (query language) |
| **OD2** | Report computation model: on-demand aggregation vs precomputed/cached aggregates vs event-sourced snapshots; how R-REP-7 is met at 100k+ items | Spike + follow-on ADR (reporting engine) |
| **OD3** | Dashboard/widget architecture and its coupling to the plugin interface (R-INT-4) | Resolved with the extension-interface spike |
| **OD4** | Time tracking (work items, per-user timesheets, estimation vs spent-time reports — a major YouTrack surface): in or out of Community scope | PO decision; if in, becomes its own requirement group + milestone |
| **OD5** | Saved-search / report / dashboard permission model detail (owner, team, tenant scopes) and its RLS mapping | Folded into the identity ADR |

## Consequences

- Backlog items **cite R-numbers** in their acceptance criteria instead of
  restating thresholds; the numbers above become the single source (500 ms
  SSE, 60 fps × 2,500 × 365, 100k-write identity target, <3 s / 100k report).
- R-UX-2/3/6 and R-REP-1/2/5/6 are **new scope** relative to the current roadmap detail: the roadmap's reporting milestone gains the query language, command dialog, distribution reports, dashboard/widget framework, and report sharing/export as explicit deliverables. The backlog ships with the repository in `.vault/`; where an item delivers a requirement, it cites the R-number.
- Follow-on ADRs planned: identity strategy, workflow model, real-time
  transport, client store & virtualization, extension/plugin loading,
  multi-tenant isolation & feature gating, identity & auth, integration &
  MCP contract, query language (OD1), reporting engine (OD2). ADRs are
  numbered in order as they are written, with no reserved numbers: the
  workflow model is ADR-VEC-03.
- Non-goals: cloning YouTrack's UI or scope wholesale; no proprietary
  code/assets are referenced or reused. Where we diverge (e.g. no built-in
  JS-scripted workflow automation in Community v1), the divergence is
  deliberate and recorded here.

## References

- [`README.md`](../../README.md) — design principles and module architecture
- [`ROADMAP.md`](../../ROADMAP.md) — milestone narrative (M1–M6)
- JetBrains YouTrack — public product documentation studied for conceptual
  inspiration on query language, command dialog, reports, and dashboards
