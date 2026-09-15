# `.vault` — this project's backlog, in the open

Most projects keep their reasoning somewhere you cannot see: a hosted tracker behind a
login, a private board, a planning repo nobody outside the team clones. The decisions that
shaped the code end up separated from the code, and a year later nobody can answer *why*
without asking someone who was there.

This project does the opposite. **The backlog lives here, in the repository, under
version control, and ships with every clone.** If you can read the source you can read the
argument behind it — what was proposed, what was rejected, what is still open, and on what
evidence.

It is also the honest thing to do for a project that *is* an issue tracker. A tracker whose
own backlog is hidden is not making much of a case for itself.

## How it works

There is no database and no server. Every item is one Markdown file with a YAML
front-matter header, so the whole backlog is greppable, diffable, and reviewable in a pull
request like any other change.

**The directory an item sits in is its status.** That is the only rule you need:

| Directory | What it means | Statuses |
|---|---|---|
| `vec/raw/` | Not started | `TO DO` · `PLANNED` · `REFINED` · `NO GO` |
| `vec/wiki/` | In flight | `IN PROGRESS` · `IN REVIEW` |
| `vec/output/` | Finished | `DONE` |

The `status:` field in the front-matter and the directory always agree; if they ever
disagree, the item is broken. Moving a file between those directories *is* the state
transition — which is why the history of this directory is a truthful record of how the
work actually went, not a tidied-up version written afterwards.

A rejected item is `NO GO` and stays in `raw/`. It never reaches `output/`, because
something that was turned down was never done. Rejected items are kept rather than deleted:
knowing what was considered and declined is usually worth more than knowing what shipped.

### Anatomy of an item

```yaml
---
kind: story          # story | enabler | spike | bug | epic | task | documentation
id: VEC-43           # unique within the space
title: ...
status: DONE
space: vec
storyPoints: 8
epic: VEC-2
---
```

Below the front-matter is prose: the problem, the decision and its alternatives, the
acceptance criteria, and — on finished items — what was actually found along the way.

### The other directories

- **`vec/sprints/`** — one file per sprint, with its goal, dates and committed items.
- **`vec/wiki/adr/`** — architecture decision records, promoted by approval status.
- **`vec/_backlog-meta.yaml`** — how identifiers are minted for this space.

`_index.md` files are generated summaries. Treat them as output, not source.

## Working with it

Because the backlog is plain files, you need no tooling to read it, search it, or change
it. `grep`, your editor, and `git log` are the whole interface:

```sh
grep -rl 'status: TO DO' .vault/vec/raw     # what is open
git log --follow .vault/vec/output/VEC-43.md  # how one item actually went
```

The working method behind this layout — the roles, the review gates, and the rule that a
decision of any consequence is recorded before the work starts — is
[meta-os](https://github.com/meta-agentic/meta-os), which is open source.

## Forking

**Forking this project is encouraged, and the backlog comes with the fork.** You inherit
the finished work, the open questions, and the reasoning behind both, so you can disagree
with a decision knowing exactly what it was and why it was taken.

If you take the project somewhere else, this directory is yours to run: keep the
convention, change it, or drop it. Nothing in the application reads these files — the
build, the tests and the runtime are completely independent of `.vault/`. It is
documentation that happens to be structured.
