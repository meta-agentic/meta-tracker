---
kind: bug
space: vec
id: VEC-48
title: npm ci and npm install both fail outright on clean main — eslint 10 against eslint-plugin-react-hooks
  5, which peer-requires eslint 9 or older
status: DONE
project: VEC
priority: P2
dependencies: []
labels:
- VEC
- bug
- tooling
- web
storyPoints: 2
estimate:
  extension: -0.2
  intension: 0.2
  quadrant: complicated
  basis: up-front
  dated: '2026-09-14'
  note: The dependency edit is one line. Complex only because the resolution is a plugin major bump that
    changes lint results, so the real work is triaging whatever new findings appear — unknown until attempted.
sprint: VEC-S3
---

# VEC-48 — `web/` cannot be installed from a clean checkout

Found during VEC-34, 2026-09-14, and recorded only in a PR body until now.

## What happens

On clean `origin/main`, both `npm ci` and `npm install` fail in `web/`:

- `eslint@^10.8.0` is declared
- `eslint-plugin-react-hooks@^5.1.0` peer-requires eslint **9 or older**

Nobody can install `web/` without `--legacy-peer-deps`, which is what the VEC-34 lane had
to use in order to do its work.

## Why it matters

A new contributor, a fresh CI runner, or a clean rebuild cannot install the frontend
without knowing an undocumented flag. `--legacy-peer-deps` also silently suppresses *other*
peer conflicts, so the workaround hides the next instance of this problem as well as this one.

## Scope note

The fix is a plugin major bump, which will change lint results. That triage is the actual
work, and it is why this is not a one-line change — the VEC-34 lane deliberately left it
rather than bundling unrelated lint churn into a localization PR. That was the right call.

## Acceptance criteria

1. `npm ci` succeeds in `web/` on a clean checkout with no `--legacy-peer-deps`.
2. `npm run lint` passes, with any rule changes from the plugin bump either fixed or
   individually suppressed with a stated reason.
3. No other peer conflict is being masked — verify by installing without the flag and
   reading the output, rather than assuming.

## Notes

- Related: VEC-49 (nothing in CI builds or tests `web/`), which is why this went unnoticed.
  A CI job installing `web/` from clean would have caught it on the commit that introduced it.
- `@testing-library/dom` was also absent from the tree entirely — an undeclared peer of
  `@testing-library/react` v16, so frontend tests could not collect on a clean install. That
  one was fixed in PR #42 as the single non-i18n dependency change, since the story's own
  verification command could not otherwise run.
