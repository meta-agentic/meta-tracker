# Contributing to Vectis

Thank you for considering a contribution to Vectis. This guide covers
everything you need to get started: the CLA, development setup, the PR
process, and the code conventions we enforce.

---

## Before your first pull request — sign the CLA

Vectis uses an automated Contributor License Agreement check. When
you open your first pull request, the CLA bot will post a comment with
a one-click sign link. The CLA:

- lets you keep your copyright — you grant a license, not ownership,
- gives the project the right to distribute your code under
  Apache-2.0 and any future OSI-approved license, and
- takes about 30 seconds to sign.

The full agreement text is in [`CLA.md`](CLA.md). If you contribute on
behalf of an employer, check whether your employer needs to sign a
Corporate CLA before you do.

The CLA check is a required status check — pull requests from
unsigned contributors cannot be merged.

---

## Security vulnerabilities

**Do not open a public issue for a security vulnerability.** Report it
privately via GitHub's [Report a vulnerability][advisory] feature
(Security › Advisories › *Report a vulnerability*). See
[`SECURITY.md`](SECURITY.md) for full details.

[advisory]: https://github.com/isohub-space/vectis/security/advisories/new

---

## Development setup

> Vectis is at the bootstrap stage: the application modules and their build are
> being established. This section describes the intended toolchain; the exact
> commands land in this file alongside the first code.

**Requirements**

| Tool | Minimum version | Notes |
|------|----------------|-------|
| JDK | 25 | Recommended: [SDKMAN](https://sdkman.io/) — `sdk install java 25.0.x-tem` |
| Maven | 3.9+ | Bundled wrapper (`./mvnw`) once the build is added |
| Docker | any recent | Required for the Testcontainers integration tests |
| Node.js | LTS | For the web client |
| PostgreSQL | — | Provided by Testcontainers; no local install needed |

---

## Opening an issue

- **Bug**: use the [bug report template][bug].
- **Feature / RFC**: use the [feature request template][feature].
- **Question**: see the contact link on the new-issue chooser.

[bug]: https://github.com/isohub-space/vectis/issues/new?template=bug_report.yml
[feature]: https://github.com/isohub-space/vectis/issues/new?template=feature_request.yml

---

## Pull request process

1. **Fork and branch.** Branch names follow the pattern `<type>/<slug>`:

   | Prefix | When |
   |--------|------|
   | `feat/` | New feature or capability |
   | `fix/` | Bug fix |
   | `sec/` | Security hardening |
   | `refactor/` | Refactor without behaviour change |
   | `ci/` | CI / tooling |
   | `docs/` | Documentation |
   | `deps/` | Manual dependency updates |
   | `test/` | Tests only |

2. **Keep one concern per PR.** A PR that fixes a bug and adds a
   feature is two PRs.

3. **Write or update tests.** New behaviour needs tests. Changed
   behaviour needs updated tests.

4. **CI must be green.** The required checks grow with the project. Today they
   are the governance checks — `dependency-review` and `cla-signed`. The build,
   test, and code-scanning checks (`build & verify`, `CodeQL`) become required
   once the application build lands.

5. **Keep history meaningful.** Fixup commits are fine during review; the
   project uses squash merges, so the final commit message is what lands on
   `main`.

6. **Commit message format.** Follow
   [Conventional Commits](https://www.conventionalcommits.org/):
   `<type>(<scope>): <short summary>`. The scope is optional but
   helpful (`domain`, `persistence`, `rest`, `web`, `ci`, etc.).

---

## Code conventions

### Architecture

Vectis is a strict hexagonal architecture:

- the domain and application-contract modules have **zero framework imports**,
- adapters (persistence, REST, real-time) depend on the application contract,
  never on each other, and
- only the composition root depends on concrete adapters.

Do not add a Quarkus or Jakarta import to the domain or contract modules.

### Multi-tenancy

Every persisted entity is tenant-scoped. Database access activates PostgreSQL
row-level security keyed to a per-transaction tenant id; a missing tenant binding
makes rows invisible rather than leaking them. Never bypass the tenant-scoped
session to query the database directly.

### Comments

Write comments only when the **why** is non-obvious. Do not comment
what the code does — use well-named types and methods for that. Do not
add TODO comments to submitted code; file an issue instead.

### SPDX headers

New source files must carry a licence identifier on the first line
(after the `package` statement or shebang for scripts):

```java
// SPDX-License-Identifier: Apache-2.0
```

---

## Code of Conduct

Be kind, assume good faith, and keep discussions focused on the
technical merits. Harassment, personal attacks, or discriminatory
language are not tolerated. Maintainers may close issues or PRs that
violate this expectation without further explanation.

---

## Questions?

Open an issue — the new-issue chooser includes a free-form option for questions.

<!-- githooks-setup -->
## Git hooks (one-time setup)

This repository ships hooks under `.githooks/` that enforce commit discipline
locally — a [Conventional Commits](https://www.conventionalcommits.org) subject
prefix, a human commit author, and no AI-attribution trailers. After cloning,
activate them once:

```bash
git config core.hooksPath .githooks
```

The same rules are enforced server-side by the `commit-lint` workflow on every
pull request, so this step is a convenience that catches issues before you push.
