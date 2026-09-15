---
kind: spike
space: vec
id: VEC-14
title: Enterprise Edition Multi-Tenant Isolation & Feature Gating Blueprints
status: REFINED
project: VEC
epic: VEC-7
storyPoints: 5
priority: P2
labels:
- Sprint-1
- VECTIS-027
- edition-community
- spike
- tier-Extensibility-Architecture
estimate:
  extension: -0.2
  intension: 0.8
  quadrant: complex
  basis: up-front
  dated: '2026-08-30'
  note: 'Complex, narrow -> spike. Low extension: a throwaway harness in one module plus a findings doc;
    no production module changes. Very high intension: R-SEC-2 mandates fail-closed RLS but ADR-VEC-07
    is unwritten, the shipped schema (V1__initial_schema.sql) has no tenant column at all, and whether
    SET LOCAL survives a shared reactive Vert.x pool is an unknown unknown that only a probe can settle.
    Time-boxed to 5 days; a negative result closes it. Anchor: VEC-9 (pk bench spike) 1, VEC-13 (plugin
    loading spike) 3 — this is broader than both.'
---

## Type

**Spike — time-boxed to 5 working days.** The item already carried a `spike`
label but was typed `kind: story`; corrected in refinement. Its deliverable is a
decision and a measurement, not a feature, and it must not be committed as
open-ended work.

## The question this spike must answer

**Can PostgreSQL row-level security, driven by a tenant claim carried on a
reactive Vert.x connection pool, deliver fail-closed tenant isolation without
leaking tenant context between pooled connections and without an unacceptable
read-path cost?**

Three sub-questions, each with a yes/no or a number as its answer:

1. **Correctness.** Does `SET LOCAL app.current_tenant_id` set inside a
   `pool.withTransaction(...)` reliably scope to that transaction only, given
   that `io.vertx.mutiny.sqlclient.Pool` hands connections back to a shared pool
   between requests? Specifically: can request B, served by the same physical
   connection immediately after request A, ever observe A's tenant setting?
2. **Cost.** What is the read-path difference between database-level RLS and
   application-level `where tenant_id = $n` filtering, measured on the real
   board read path across a simulated 1,000-tenant dataset?
3. **Gating seam.** Where does the Community open-core code hand off to
   Enterprise-gated security behaviour (SOC 2 audit logging, SCIM), given that
   `vectis-core`'s `ExtensionRegistry` + `vectis-extension-spi` (`AuditLogger`,
   `SyncConnector`) — delivered by VEC-13 — is the seam that already exists?

## Why this is genuinely open

**R-SEC-2** in ADR-VEC-01 mandates that tenant isolation fails closed via
PostgreSQL RLS — *missing tenant binding ⇒ rows invisible*. That is the
requirement. The **mechanism** is undecided: ADR-VEC-07 (multi-tenant isolation
& feature gating) is listed in ADR-VEC-01 as a planned follow-on and has not
been written. Meanwhile the persistence slice shipped by VEC-41 has **no tenant
column anywhere** — `V1__initial_schema.sql` defines `workspace`, `board`,
`board_column` and `item` with no `tenant_id` and no RLS policy — and
`WorkspaceRepository` / `BoardRepository` / `ItemRepository` all issue plain
`pool.preparedQuery(...)` with no session variable. So there is nothing to
measure against yet; the spike must build its own throwaway harness. This is
what makes it a probe rather than an analysis.

## Scope

In scope — throwaway code, on a spike branch, not merged into the product path:

* A migration in a scratch schema adding `tenant_id` plus an `enable row level
  security` policy keyed on `current_setting('app.current_tenant_id', true)`,
  over a copy of the `item` table shape from `V1__initial_schema.sql`.
* A concurrency probe that hammers the pool with interleaved requests from
  distinct tenants and asserts zero cross-tenant reads — the leak test for
  sub-question 1. A single observed leak is a decisive negative result and ends
  the spike early.
* A 1,000-tenant seeded dataset and a benchmark comparing RLS vs application-level
  filtering on `ItemRepository.findByColumn`, following the harness convention
  VEC-9 established in `docs/spikes/vec-9/`.
* A written blueprint mapping the Community → Enterprise gating seam onto the
  existing `ExtensionRegistry` / SPI, naming the extension points SOC 2 audit
  logging and SCIM would attach to.

Explicitly out of scope:

* Any production migration, schema change or repository change on `main`. The
  spike ships findings and throwaway code, never the feature.
* OIDC itself, and the `OidcTenantConfigResolver` — that is **VEC-32**, which
  depends on this spike's answer to sub-question 1. Coordinate, do not merge the
  two.
* Implementing SOC 2 audit logging or SCIM. The blueprint names the seam; it does
  not build the plugins.
* Deciding whether tenancy is in Community edition at all. That is a PO call this
  spike informs.

## Acceptance criteria

1. A findings document exists at `docs/spikes/VEC-14-tenant-isolation.md`,
   following the shape of `docs/spikes/VEC-13-plugin-loading.md`, and answers
   all three sub-questions explicitly. A sub-question answered "unclear" is an
   acceptable *result* only if the document states what was tried and what would
   settle it.
2. Sub-question 1 is answered with a **reproducible test**, not an argument: the
   concurrency probe is checked in with its command line, and the document
   records pass/fail plus the pool configuration under which it ran.
3. Sub-question 2 is answered with **numbers on the board read path** —
   p50/p95 latency for RLS and for application-level filtering, at 1,000 tenants,
   stating dataset size, hardware and Postgres version. A ratio without absolute
   numbers is not an answer.
4. The document names an explicit **recommendation** — RLS, application-level
   filtering, or both in layers — with the evidence for it, in a form that can be
   lifted directly into ADR-VEC-07.
5. Sub-question 3 is answered as a diagram or table mapping each named Enterprise
   capability to a concrete extension point in `vectis-extension-spi`, or states
   which capability the current SPI cannot host and why.
6. The spike's throwaway code either lives under `docs/spikes/vec-14/` (the VEC-9
   convention) or is left unmerged on its branch and linked from the document.
   Nothing from it lands in `vectis-persistence/src/main`.
7. `mvn -B -ntp verify` on `main` is unaffected — the spike must not change the
   product build.

## Exit condition

The time-box ends at 5 working days regardless of outcome. A negative result
("RLS leaks under our pool configuration", or "RLS costs 4× on the read path")
is a **successful** spike and closes it. If sub-question 1 fails on day one, stop
and report — sub-questions 2 and 3 are moot without it.

## Feeds

ADR-VEC-07 (multi-tenant isolation & feature gating) — currently unwritten and
blocking VEC-32, VEC-33 and VEC-35.
