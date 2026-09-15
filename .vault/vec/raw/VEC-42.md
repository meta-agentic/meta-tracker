---
kind: story
space: vec
id: VEC-42
title: Replace the synthetic workspace generator with a real API client in the SPA
status: REFINED
project: VEC
epic: VEC-3
priority: P0
labels: []
storyPoints: 5
estimate:
  extension: 0.4
  intension: -0.2
  quadrant: complicated
  basis: up-front
  dated: '2026-08-30'
  note: 'Complicated: a new api/ module, a wire->store adapter, hydration replacing the generator, four
    loading/error states, and test coverage for each — several parts, all in web/. Intension low but not
    negative-heavy: the client-side shape is fully known (WorkspaceSnapshot already exists and is documented
    as the REST/SSE payload, cache.ts already does the layering), and the one genuinely undefined thing
    — the endpoint contract — is owned by VEC-10, so it is a blocking dependency rather than an unknown
    this story must resolve. Refined but NOT pullable until VEC-10 (itself blocked on VEC-45) lands.'
---

## Problem

The web client never talks to a server. `web/src/App.tsx` seeds itself with
`generateWorkspace({ boards: 5, epics: 18, issues: 4_000, years: 3 })` from
`src/lib/synthetic.ts`, and there is no HTTP client anywhere in the SPA — no
`fetch` wrapper, no API module, nothing in `package.json` beyond react, zustand
and `@tanstack/react-virtual`. Everything the UI displays today is generated in
the browser and discarded on reload.

This is a genuine ownership gap: **no other story in the backlog says to stop
doing this.** VEC-20 (store with cache layering) and VEC-21 (dual-axis
profiling) are both DONE and both were built against synthetic data *by design*,
which was correct for proving the architecture and leaves the client unwired.
`src/store/types.ts` even documents the intent — `WorkspaceSnapshot` is
commented as *"a bulk payload as it would arrive from the reactive REST/SSE
edge"* — but nothing produces it from a server.

## Scope

In scope:

* A typed API client module under `web/src/api/`, covering the workspace, board
  and item read endpoints, with the wire types derived from the endpoints VEC-10
  defines rather than invented here.
* An adapter from the wire shape to the store's `WorkspaceSnapshot`
  (`src/store/types.ts`), so the store's normalized shape stays independent of
  the transport.
* Hydrating the store from the server in place of the generator, preserving the
  existing cache-layer behaviour in `src/store/cache.ts` — cached data renders
  immediately and is revalidated in the background, so board switching stays
  instant (R-UX-5).
* Loading and error states, which the synthetic path never needed: first load,
  revalidation failure with stale cache present, and hard failure with no cache.
* Keeping `src/lib/synthetic.ts`, scoped to tests and the profiling harness only.

Explicitly out of scope:

* **Write paths.** This story makes the board *show* server data. Item creation
  and mutation belong to VEC-16; live updates belong to VEC-17.
* Authentication and any `Authorization` header — VEC-35. The client is written
  so a request interceptor can be added without touching call sites, and no
  further.
* Defining the endpoints. VEC-10 owns the API contract; this story consumes it.
* Any change to the profiling harness's data path.

## Acceptance criteria

1. The Boards tab renders boards, columns and items served by the backend, and
   they are still there after a page reload.
2. `src/App.tsx` contains **no import from `src/lib/synthetic`**. Verified by
   `grep -rn "lib/synthetic" web/src --include=*.tsx --include=*.ts` returning
   matches only under `src/profiling/`, `src/lib/synthetic.test.ts` and other
   test files — the generator must not be reachable from the application entry
   path.
3. The timeline profiler still runs standalone against generated data with no
   server running. The 100k-item profiling presets must not require a backend;
   `npm run test` passes with nothing listening on the API port.
4. On a cold load with an empty cache, a loading state is shown and replaced by
   real data; the app never renders an empty board as though it were a loaded
   empty board.
5. On a warm load, cached data paints first and is replaced by revalidated data
   without a visible remount or a scroll reset (R-UX-5).
6. A failed revalidation with cached data present keeps the cached board on
   screen and surfaces a non-blocking staleness/error indicator — it does not
   blank the board.
7. A hard failure with no cache shows an error state with a retry affordance, not
   a blank screen or an unhandled promise rejection.
8. The API client is typed end to end: no `any` crosses the module boundary, and
   `npm run typecheck` passes.
9. Vitest coverage for the adapter (wire shape → `WorkspaceSnapshot`) and for
   each of states 4–7, using a mocked transport — the suite must not require a
   live server.

## Verification

From `web/`:

```
npm run test
npm run typecheck
npm run lint
```

Plus the grep in criterion 2, and a manual check of criteria 1 and 5 against a
running `vectis-server`.

## Requirement trace

R-UX-5 (instant navigation: cached data immediately, revalidate in background)
from ADR-VEC-01 §2. Roadmap M3 (client store optimization with cache layering).

## Dependencies — blocking

**VEC-10** (provisioning endpoints), which is itself currently blocked on
**VEC-45** (template → instance model spike). This story is *refined but not
pullable*: its acceptance criteria are stable because they describe client
behaviour, but there is no server-side endpoint to call yet — `vectis-server`
contains exactly one resource, `ExtensionDiagnosticsResource.java`. Do not
schedule this before VEC-10 lands; the alternative is inventing an API contract
here, which is precisely the mistake VEC-45 exists to prevent.
