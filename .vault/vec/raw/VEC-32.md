---
kind: spike
space: vec
id: VEC-32
title: Dynamic Multi-Tenant OIDC Resolution Feasibility
status: REFINED
project: VEC
epic: VEC-7
storyPoints: 5
priority: P2
labels:
- Sprint-6
- VECTIS-022
- edition-community
- spike
- tier-Core-IAM
dependencies:
- VEC-14
estimate:
  extension: -0.4
  intension: 0.7
  quadrant: complex
  basis: up-front
  dated: '2026-08-30'
  note: 'Complex, narrow -> spike (item was already labelled spike but typed kind:story; corrected). Low
    extension: one throwaway resolver, two mock tenants, one bench, one findings doc — nothing in vectis-server/src/main
    changes. High intension: ADR-VEC-08 is unwritten, vectis-server has no OIDC dependency and exactly
    one resource class, the schema has no tenant concept, and the reactive-resolver claim in the developer
    notes is an assertion about the pinned Quarkus version that nobody has checked. The 10ms budget was
    asserted, not measured. Time-boxed to 3 days; sequenced after VEC-14.'
---

## Type

**Spike — time-boxed to 3 working days.** The title already said "Feasibility"
and the item already carried a `spike` label, but it was typed `kind: story`;
corrected in refinement. The deliverable is a prototype and a measurement, not a
shipped authentication path.

## The question this spike must answer

**Can a tenant's OIDC configuration be resolved per request, at runtime, from a
non-blocking source, inside Quarkus's `OidcTenantConfigResolver` — without
blocking the event loop and without adding meaningful latency to the handshake?**

Sub-questions:

1. **Contract shape.** Does the `OidcTenantConfigResolver` SPI in the Quarkus
   version this repo pins actually admit a reactive return
   (`Uni<OidcTenantConfig>`), or does it force a synchronous resolution? The
   original developer note asserts the reactive form; the spike must *confirm* it
   against the pinned version rather than repeat the assertion.
2. **Where config comes from.** If the resolver must not block, a per-request
   database lookup on the event loop is prohibited. What is the source — a
   reactive query, a warmed in-memory cache with reactive refresh, or a
   config-file map — and what is the invalidation story when a tenant changes
   IdP "on the fly without configuration reboots", which is the whole point of
   the story?
3. **Cost.** Does resolving a custom tenant endpoint add less than the 10 ms of
   handshake overhead the original acceptance criterion asserts? That number was
   asserted, not derived; the spike either validates it or replaces it with a
   measured one.
4. **Fallback.** Does an unmatched/unknown tenant route cleanly to the internal
   application authentication server, and — the security-relevant half — does an
   unmatched tenant ever fall back to *another tenant's* configuration?

## Why this is genuinely open

ADR-VEC-01 **R-SEC-1** decides the *requirement* (multi-tenant OIDC with
federated IdPs, client-managed Authorization Code + PKCE, backend as a pure
resource server). **ADR-VEC-08 (identity & auth) is listed as a planned
follow-on and has not been written.** Nothing in the repo authenticates anything
today: `vectis-server` contains exactly one resource
(`ExtensionDiagnosticsResource.java`), there is no OIDC dependency in
`vectis-server/pom.xml`, and there is no tenant concept in
`V1__initial_schema.sql`. There is no code here to extend — only a question to
answer.

## Depends on

**VEC-14** (multi-tenant isolation & feature gating spike). VEC-14 answers
whether a tenant identity can be carried safely across the reactive connection
pool; this spike answers where that identity comes from. Running this one first
risks designing a resolver for a tenant-context mechanism that VEC-14 then rules
out. Sequence VEC-14 → VEC-32.

## Scope

In scope — throwaway prototype on a spike branch:

* A minimal Quarkus app (or a scratch profile in `vectis-server`) with
  `quarkus-oidc` added, two mock tenant configurations, and a
  `OidcTenantConfigResolver` implementation selecting between them from the
  inbound request's subdomain or context path.
* An assertion, by test or by instrumentation, that the resolver does not block —
  e.g. a blocking call inside it is detected, or the Vert.x blocked-thread
  checker stays silent under load.
* A handshake-latency benchmark: resolver present vs absent, reporting the delta.
* A negative test for the fallback path: an unknown tenant reaches the internal
  auth server and never inherits a configured tenant's settings.

Explicitly out of scope:

* Shipping authentication. **VEC-35** is the story that implements client-managed
  federated OIDC; this spike de-risks it and must not pre-empt it.
* Tenant *data* isolation and RLS — VEC-14.
* User preferences bound to an identity claim — VEC-33.
* Token introspection strategy, refresh handling, session management, and
  logout — all belong to ADR-VEC-08 and VEC-35.

## Acceptance criteria

1. A findings document exists at `docs/spikes/VEC-32-oidc-tenant-resolution.md`,
   following the shape of `docs/spikes/VEC-13-plugin-loading.md`, answering all
   four sub-questions explicitly.
2. Sub-question 1 is answered by citing the resolver interface signature **from
   the Quarkus version pinned in this repo's `pom.xml`**, with the pinned version
   recorded in the document.
3. A working prototype resolves at least two distinct mock tenants from the
   request and is checked in under `docs/spikes/vec-32/` or left on its branch and
   linked, with the command to run it.
4. Non-blocking behaviour is demonstrated by a check that would **fail** if the
   resolver blocked — not by inspection of the source.
5. Handshake overhead is reported as a measured number with its method, and the
   document states plainly whether the asserted 10 ms budget holds. If it does
   not, the document proposes the number that should replace it in ADR-VEC-01.
6. The fallback path is covered by a test asserting both halves: unknown tenant →
   internal auth server, and unknown tenant → **never** another tenant's config.
7. The document names a recommended resolution source and invalidation strategy
   (sub-question 2) in a form liftable into ADR-VEC-08.
8. `mvn -B -ntp verify` on `main` is unaffected; nothing from the prototype lands
   in `vectis-server/src/main`.

## Exit condition

3 working days. "The pinned Quarkus version does not admit a reactive resolver"
is a **successful** outcome and closes the spike — it redirects VEC-35 rather
than stalling it.

## Feeds

ADR-VEC-08 (identity & auth) — currently unwritten and blocking VEC-33 and
VEC-35.
