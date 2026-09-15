---
kind: bug
space: vec
id: VEC-50
title: 'JVM path filters are unanchored, so a future .sql or .java file under web/ would spend a
  Maven build and a Postgres service on an SPA-only change'
status: TO DO
project: vectis
priority: P3
dependencies: []
labels:
- VEC
- ci
storyPoints: 1
---

# VEC-50 — the workflow filters are disjoint by accident, not by construction

Found by independent review of VEC-49 (PR #44) on 2026-09-15.

## What is wrong

`jvm-test.yml` filters on `**/*.java` and `**/*.sql`. Both patterns are **unanchored**, so
they match at any depth anywhere in the tree — including under `web/`.

Today that costs nothing, and the reviewer established this exhaustively rather than by
sampling: across all **26 tracked files under `web/`**, none matches any `jvm-test.yml`
filter. The `vectis-*/src/**` scoping is what currently keeps `web/src/**` out, and it is
load-bearing.

But the disjointness holds **over today's tree, not structurally**. The first time someone
adds, say, `web/src/test/fixtures/seed.sql`, an SPA-only pull request will match both
filters and spend a full Maven build plus a Postgres service container on a change that
touches no Java at all.

## Why it is filed separately

This is `jvm-test.yml`, which is VEC-44's file (PR #41, unmerged) — not VEC-49's. The
reviewer explicitly declined to make it a change request against PR #44 for that reason.
Anchoring is cheap insurance, but it belongs to whoever owns that workflow.

## Acceptance criteria

1. `**/*.java` and `**/*.sql` in `jvm-test.yml` are anchored to the JVM modules, e.g.
   `vectis-*/**/*.java` and `vectis-*/**/*.sql`.
2. A pull request touching only files under `web/` triggers no Maven build and starts no
   service container — demonstrated on a real PR, not asserted.
3. The `push` trigger gets `paths:` filters too. **Confirmed defect, not a maybe:**
   `push: branches: [main]` in `web-ci.yml`, `jvm-test.yml` and `native-deploy.yml` all
   carry no `paths:` filter at all, and path filters apply only to `pull_request`. So every
   merge to main runs all three workflows regardless of what changed — a web-only merge
   triggers a real `mvn verify` (Maven + a Postgres service, up to its 30-minute timeout)
   and, once modules exist, the ~60-minute native build.

## Notes

- Depends on VEC-44 (PR #41) landing, since the file only exists on that branch.
- Related: a second follow-up worth its own item if React Compiler is ever enabled in
  `web/vite.config.ts` — the `react-hooks/purity` suppression in `ProfilingHarness.tsx`
  would then hide a genuine correctness problem rather than an advisory one. React Compiler
  is not enabled today (`vite.config.ts` uses a bare `react()`).
