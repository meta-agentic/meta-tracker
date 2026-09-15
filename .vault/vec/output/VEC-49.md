---
kind: story
space: vec
id: VEC-49
title: Nothing in CI builds or tests web/ — the SPA has no automated check at all, and VEC-44 closes only
  the Java half of that blind spot
status: DONE
project: VEC
priority: P2
dependencies:
- VEC-48
labels:
- VEC
- ci
- web
storyPoints: 3
estimate:
  extension: 0.2
  intension: -0.2
  quadrant: complicated
  basis: up-front
  dated: '2026-09-14'
  note: One workflow mirroring the JVM one added by VEC-44 (moderate extension, low intension — the shape
    is already established). Blocked behind VEC-48 only because a job that cannot npm ci is not worth
    writing yet.
sprint: VEC-S3
---

# VEC-49 — The SPA has no CI

Found during VEC-34 and VEC-44, 2026-09-14.

## What happens

No workflow builds, lints, type-checks or tests `web/`. VEC-44 added `jvm-test.yml`, which
closes the Java half of the gap — the frontend half stays open.

So every frontend change to date has been merged on local evidence alone.

## Why it matters

Two defects found in a single sprint were each invisible because of this:

- `npm ci` fails outright on clean `main` (VEC-48). A CI job installing from clean would
  have failed on the commit that introduced it.
- `@testing-library/dom` was missing from the tree entirely, so frontend tests could not
  collect on any clean install — the suite passed only for people whose local `node_modules`
  predated the gap.

Both are the same class: a check that only ever runs on a developer's warm machine does not
notice when a clean environment breaks.

## Scope

A workflow mirroring `jvm-test.yml`'s shape: install from clean, then type-check, lint,
test and build. Path-filtered to `web/**` so a Java-only PR does not pay for it — mirroring
the scoping decision VEC-44 made in the other direction, which was reviewed and confirmed
correct.

## Acceptance criteria

1. A PR touching only `web/**` runs a frontend check and no Maven build.
2. A PR touching only Java runs the JVM check and no frontend build.
3. The frontend job installs from a clean cache with no `--legacy-peer-deps`.
4. The job fails if type-check, lint, test or build fails — verified by pushing a
   deliberate breakage, not asserted.

## Notes

- Depends on VEC-48: a job that cannot `npm ci` is not worth writing first.
- Blocked in practice, like everything else in this repo, until the GitHub Actions
  billing failure on its former private home was resolved — no workflow could be
  observed running until then.
