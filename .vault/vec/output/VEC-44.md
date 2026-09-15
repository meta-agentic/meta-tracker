---
kind: story
space: vec
id: VEC-44
title: native-deploy.yml's path filter silently excludes source-only PRs from ever running tests
status: DONE
project: VEC
priority: P1
labels:
- CI
- infra
storyPoints: 1
estimate:
  extension: -0.7
  intension: -0.6
  quadrant: simple
  basis: up-front
  dated: '2026-08-30'
  note: 'One workflow file (optionally one new sibling); no application code touched. The fix is known
    and the two candidate shapes are both mechanical and reversible — the only open point is a maintainer
    preference, not a design question. Anchor: VEC-12 (whole native+k8s+cosign pipeline) was 5; a trigger-surface
    correction is well under that.'
sprint: VEC-S3
---

## Problem

`native-deploy.yml` is the only workflow in this repo that stands up a
PostgreSQL service, and therefore the only one that runs the persistence test
suite (`vectis-persistence/src/test/java/io/vectis/persistence/PersistenceRoundTripTest.java`
— Quarkus Dev Services is deliberately disabled in that module's test config, so
CI has to supply the database). Its `pull_request` trigger is path-filtered to
`**/pom.xml`, `deploy/**`, and `.github/workflows/native-deploy.yml`.

A pull request that touches only `.java` or `.sql` matches none of those paths,
so the job never fires. The checks that *do* run on a source-only PR
(`cla-signed`, `dependency-review`, `commit-lint`, `scorecard`, `supply-chain`)
do not execute a single test. Found by `vec-tester` while shipping VEC-43
(PR #28), but it is not specific to that PR: it applies to every source-only PR
in the repo, which means a developer's local `mvn clean verify` is today the
*only* verification the persistence suite ever receives.

## Scope

In scope: the `pull_request` trigger surface of `.github/workflows/native-deploy.yml`,
and — if the chosen shape is a split — one new workflow file plus its Postgres
service block. Both shapes are acceptable; the choice is a maintainer call:

* **(a) Extend the filter** — add `**/*.java`, `**/*.sql`, `**/src/**` to the
  existing `paths:` list. Smallest diff; costs a full ~60-minute GraalVM native
  build on every source PR.
* **(b) Split a JVM test job out** — a `mvn -B -ntp verify` job (no `-Pnative`)
  with the same Postgres service, triggered on source paths; `native-deploy.yml`
  keeps its current narrow filter for the native/packaging path. Larger diff,
  much faster PR feedback.

(b) is the recommended shape — a native compile is a packaging gate, not a test
gate, and PR feedback measured in minutes rather than an hour is the point of
running tests on a PR at all. Either satisfies the acceptance criteria.

Explicitly out of scope:

* The two pre-existing red checks — `cla-signed` (the `cla-signatures`
  signature-store branch was never created) and `dependency-review` (the repo
  does not have GitHub dependency-graph enabled). Both need repo-admin/GitHub
  settings changes, both were already red on the previously merged PR #17, and
  neither is a regression. Recorded here for visibility only.
* Any change to the `image` job, cosign signing, SBOM/provenance attestation, or
  the Argo CD staging path.
* Adding new tests. This story makes the tests that already exist actually run.

## Acceptance criteria

1. A pull request whose diff contains only `.java` and/or `.sql` files triggers
   a workflow job that runs the Maven test phase against a real PostgreSQL
   service container. Verified by inspecting the checks on such a PR: a
   test-running check is present and green.
2. That job executes `PersistenceRoundTripTest` — the jsonb / uuid-ordering /
   GIN-containment round trip — against `postgres:16-alpine`, not an in-memory
   substitute, with the same `VECTIS_DB_*` environment contract the current
   `native-build` job uses.
3. The Postgres service keeps a `pg_isready` health gate, so the build does not
   race the database on a fast runner.
4. The `image` job still never runs from a `pull_request` event
   (`if: github.event_name != 'pull_request'` semantics preserved) — no image is
   published from an untrusted PR context.
5. `deploy/**`- and `**/pom.xml`-only PRs continue to validate the native build,
   i.e. no existing coverage is traded away for the new coverage.
6. The workflow file(s) pass `actionlint` and every third-party action stays
   pinned to a commit SHA with a version comment, matching the convention
   already used throughout `.github/workflows/`.

## Verification

* `mvn -B -ntp verify` from the repo root must still pass locally (the change is
  CI-only and must not alter build behaviour).
* Proof of the fix is observational and belongs in the PR: open the PR with a
  source-only diff and link the run of the test-executing check from its checks
  list. A green run on a PR that would previously have run no tests is the
  acceptance evidence.
