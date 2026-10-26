# Tracker adapter contract, part 2 (spike) — input to the integration & MCP contract ADR

| | |
|---|---|
| **Status** | Concluded — decisions taken by the PO on 2026-10-21 and recorded in [ADR-VEC-04](../adr/ADR-VEC-04-integration-contract-and-agent-surface.md) (Accepted 2026-10-22) |
| **Type** | Spike (architect, Extensibility & Data tier), VEC-99 under epic VEC-47. Continues the read-side spike VEC-53 and the read side delivered by VEC-57 |
| **Requirement** | [ADR-VEC-01](../adr/ADR-VEC-01-product-requirements-and-features.md) **R-INT-2** (bidirectional mapping), **R-INT-3** (agents through the same guardrails), **R-INT-4** (extension interface), **R-CORE-2** (identity), **R-CORE-4** (SSE), **R-CORE-6** (re-keying); **R-SEC-1/2** as constraints |
| **Inputs** | The SPI as merged (`BacklogSource`, `BacklogSnapshot`, `SourceItem`, `StatusCategory`, `Outcome`), `vectis-connector-vault`, the V3 event write path, the `.vault/` convention ([`.vault/README.md`](../../.vault/README.md)), and a six-lane literature and product survey dated 2026-10-21 (derived-index and GitOps systems, in-repository issue trackers, bidirectional sync products and reconciliation theory, change detection, connector protocols, MCP) |
| **Out of scope** | Product code, migrations, UI. The import wiring is VEC-54's; the read endpoints are VEC-94's |

## Question and answer

> Is Vectis the system of record or a projection over a canonical backlog kept as files; how
> does a write in Vectis reach those files and what happens on conflict; how is identity
> reconciled when two sources use the same key; does binding a repository of backlogs mean
> live watching or periodic reconciliation; what vocabulary does the SPI need; and where does
> the agent surface sit?

**Vectis is a reconciling projection, never a second system of record.** A write to a
file-backed item is a *command* replayed through the backlog format's reference writer on a
checkout at the command's base commit, pushed with a compare-and-set on the branch head, and
shown as settled only after the push lands and the next reconcile observes it. Identity is a
crosswalk, `(workspace, source, key, item id, path, blob hash)`: the display key is never the
identity, two sources sharing a key bind to two workspaces, and keys for a file-backed
workspace are allocated only by the writer's serialised counter. A binding implies a
level-triggered reconcile on a jittered timer, with every file watcher, hook and webhook
demoted to a hint. The SPI keeps the full snapshot as the baseline and adds capabilities as
data, an opaque host-owned cursor, sealed changes with tombstones, an optional sink and a
two-bucket report; `SyncConnector` becomes the composite of source and sink, selected by id.
The MCP surface sits inside the service on the command layer the REST resources use, which is
the only placement that gives R-INT-3 by construction.

Every rule below names the precedent it rests on. The survey's full citations are in the
project backlog's spike record; the precedents are public documentation and source.

## 1 · Projection, not record

The one tool that ran "files canonical plus a derived database, newest wins", with writes
landing in the database first, logged stale-database overwrites, resurrected deletes and
split-brain failures and abandoned the two-store model (Beads, before its move to a single
store). The systems that survived treat the database as a cache rebuilt from content
identity (Backstage's catalog, "a live-updating view of an actual authoritative data source",
with no entity write endpoint; TinaCMS, "more of an ephemeral cache"; Fossil's ticket tables,
rebuilt from artifacts), surface divergence instead of healing it (Argo CD `OutOfSync` with
`selfHeal` off by default) and gate deletion separately (Argo CD and Flux `prune`).

What that fixes for the importer, on top of VEC-53's idempotent upsert:

1. **Inventory by content.** Per item: source id, path, blob hash, derived revision. A
   reconcile diffs blob hashes and touches only changed rows; an out-of-band push is seen on
   the next scan. Never modification time.
2. **Last good row.** A file that fails validation keeps its previous row, with the problem
   attached; nothing blanks a board because one file is malformed.
3. **Visible divergence.** A row with a pending or conflicted write whose file moved shows
   `OUT_OF_SYNC`, with the revision it was derived at.
4. **Gated prune.** Only rows whose inventory entry vanished from a *complete* snapshot are
   tombstoned; never after an empty or incomplete read.
5. **An idempotence test in CI.** A full re-list at any commit regenerates identical rows and
   the second run appends zero events.

Rejected: Vectis as a second system of record with newest-wins reconciliation; the files as a
mere export of Vectis.

## 2 · Write-back is a command replayed at the base, never a merge of text

Every safe write-back precedent is optimistic concurrency keyed on a base identifier: HTTP
`If-Match` and 412 ("to prevent the lost update problem", RFC 9110); GitHub's contents API,
where the blob SHA is required to update a file and concurrent writes to one path must be
serialised; git's own non-fast-forward refusal and `--force-with-lease`; and Keystatic, the
one git-backed CMS whose rule could be read in source: commit with the expected head, on a
stale head retry only if the files it touched are unchanged, otherwise refuse. Textual merge
of YAML is ruled out by git's own semantics (edits to adjacent keys are one hunk; a file moved
between status directories reads as delete plus add), and the backlog format already applies
the right rule to its generated files: regenerate, never hand-merge.

**The loop.**

1. A mutation on a file-backed item records, in the transaction that bumps `item.version`
   and appends the workspace event, a command `{key, base commit, base blob hash, field
   deltas, principal, actor, channel, idempotency key}` and flags the row pending. Clients see
   the pending state over SSE.
2. One worker per workspace drains commands in order under the workspace's advisory lock; a
   failing command parks and does not block the queue.
3. The worker checks out the backlog at the base commit and applies the deltas **through the
   format's reference writer** (the transition, sprint and estimate commands of the backlog
   tooling), never by writing a file: the writer is what keeps status and directory in
   agreement and stamps the sprint in the same step. It commits with a message that carries
   the item ids and the cross-reference.
4. It pushes with a lease on the base commit. On success the command is landed with its SHA;
   the row stays pending until the reconcile loop observes that SHA or a descendant with the
   same blob, which is how a write-back echo is recognised without a special channel.
5. On a non-fast-forward: fetch; if the item's blob at the new head equals the base blob,
   rebase and retry (bounded); if it changed, reconcile field by field.

**Field-level reconciliation** follows the Unison file synchroniser's definitions (archive =
last synchronised state; a field changed on one side propagates; changed on both is a
conflict; the archive advances only where both sides agree) with ownership deciding which
fields can conflict at all:

| Field class | Owner | Rule |
|---|---|---|
| status, directory, sprint stamps, completion date, delivery credit, id | the reference writer | Vectis asks for a transition; the writer decides. No conflict is possible because the command is a request, not a value |
| title, estimate, priority, labels, parent, dependencies | shared scalars | canonical-wins when both changed; the Vectis value stays on the command as a conflict record, both values visible, manual queue |
| body sections, comments, links | append-only | append-merge, never a conflict |
| existence | the files | Vectis never deletes a file; a delete is a crosswalk tombstone plus a notification. Aborted work is the format's own discontinued status, through the writer |

Wall-clock last-writer-wins does not appear: every tracker-sync product that documents it
(Exalate, TFS4JIRA, Stacksync) also documents the clock and ordering trouble it causes.

**Precondition: a public reference writer.** The format's writer is today tooling of the
operating-model instance, not of the public framework, and its behaviour is the format's only
specification. Reimplementing it in Java would make Vectis a second writer of a format that
keeps gaining rules. The design therefore assumes the writer is promoted to the public
framework and runs beside Vectis: in development as a local process over a checkout, in
Kubernetes as a sidecar that owns the checkout and the push credentials and takes commands
over localhost. Vectis talks to a `VaultWriter` port; push credentials never enter the
service container. Until then, write-back on a file-backed workspace stays off and the import
is read-only (VEC-54), which the client must say on imported items.

**First increment.** Status transitions, sprint membership and estimates: the commands the
writer already has. Title and body follow once the writer gains a field-setting command.

## 3 · Identity: crosswalk, counter, doctor

The display key is never the identity. Every tracker that imports from another keeps the
foreign key as metadata beside its own stable id (Jira's External Issue ID; GitHub's opaque
`node_id` beside `number`; Linear's UUID `id` beside `identifier` = team key plus a
team-scoped number; git-bug's per-operation provider ids), and master-data management
resolves "two sources, one key" with a crosswalk per source so a collision is a match
decision, never an overwrite.

- **Crosswalk** per row: `(workspace_id, source_id, key, item_id, path, blob_sha)`. The
  existing `unique (workspace_id, key)` stays; the UUIDv7 is the identity across re-keying
  (R-CORE-6: a new key adds a crosswalk row and keeps the old as an alias, because reassigning
  keys is what breaks links, as Jira's import issues record).
- **Two sources, one key**: one workspace per `(source, space)`; they never meet.
- **Two files, one key inside a source** (a stale copy after a move, or two branches merged
  without conflict): the moved-copy case keeps VEC-53's "further tier wins, with a problem";
  a duplicate in the same tier is a conflicted row that keeps the path it was derived from
  (Backstage's `locationKey` rule) and is never silently dropped. The incident to design
  against is Backlog.md's duplicate `TASK-123` (2026-05): both files merged cleanly and a map
  keyed by id dropped one on every load.
- **Allocation.** No surveyed tool allocates human-readable sequential keys safely across
  divergent histories by scanning; Backlog.md's two fixes (merged 2026-07 and 2026-08) still
  leave a race window, and Beads moved to hash ids. A serialised, committed counter (fetch
  the main branch, commit the bump on top of it, push, retry on a race) is the only shape
  that works, and it is what the format's writer already does. **Vectis never mints a key for
  a file-backed workspace**: creating an item there is a command the writer fulfils with its
  allocator and the new file. This closes the key-minting risk VEC-53 recorded.
- **Doctor.** The format's gate detects duplicates; a repair that renumbers the newer
  duplicate and rewrites every reference (dependencies, relations, parent, the generated
  dependency index) in one commit is the missing tool.

## 4 · A binding means a timed reconcile; watching only shortens latency

Every mature system examined states it the same way: reconciliation is the guarantee, events
are an optimisation ("Level driven, not edge driven … Watches, etc, are all merely
optimizations", Kubernetes controller guide; Argo CD polls at 120 s plus up to 60 s of
jitter and treats a webhook as a refresh that "already occurs"; Flux requires an interval
and adds receivers "alongside"; git-sync polls every 10 s and documents its hooks as
at-least-once and coalescing; GitHub states a 10-second webhook deadline and no automatic
redelivery).

**The binding** is a list of `{space, path}` rows (the operating model's instance config
already carries one); one workspace per space, provisioned by reference from the Scrum
template with the Refined delta; discovery falls back to every `<root>/<space>/` that
carries the space's meta file. One binding, not one integration per space.

| Deployment | Hint | Guarantee | Generation token |
|---|---|---|---|
| Local checkout | `WatchService` per directory (Linux inotify; macOS remains a 2 s polling implementation, JDK-8293067 open); OVERFLOW, a failed key reset and an exhausted inotify budget all request a reconcile; `.git/**` and editor temporary names ignored | reconcile every 30 s ± 25 %; a poll-only switch for NFS, Docker Desktop, WSL2 | `HEAD` plus a working-tree digest (path, size, mtime, inode) so uncommitted edits count |
| Kubernetes with a git-sync sidecar and a read-only root filesystem | the sidecar's webhook to `localhost`, answered 202 within its 1 s timeout; hooks are at-least-once and may skip hashes; never a watch inside the worktree, which dies on the symlink flip | reconcile every 60 s ± 25 % that reads the link target | the commit hash in the link target |
| GitHub-hosted repository | `push` webhook with the signature verified and the delivery id deduplicated, answered within 10 s and read only as "ref changed" | reconcile every 60 s ± 25 % by a conditional request on the branch (a 304 is free of the primary rate limit) | the branch SHA; per-file blob SHAs from the recursive tree |

Common to all three: a 500 ms quiet-period debounce with a 5 s cap; a reconcile that returns
at once when the token equals the last one; the workspace advisory lock taken with the `try`
variant so a second replica simply returns; an SSE event appended only when `added +
changed + removed > 0`, carried to other replicas over `NOTIFY` as the real-time transport
spike specifies. This also settles the VEC-53 risk that the native image cannot see
`.vault/`: the sidecar materialises the checkout into a volume mounted read-only into the
service. The intervals are engineering choices bracketed by the cited defaults, not values
any source prescribes for a Markdown backlog.

## 5 · The SPI vocabulary

The data-integration protocols converged independently on the same decomposition: a
baseline full refresh every connector supports and incremental as a declared capability
(Airbyte sync modes, Singer replication methods, Debezium snapshot modes with `when_needed`);
a cursor whose format the connector owns and whose lifecycle the host owns, advanced only
after the write side acknowledged (Airbyte's state rule, Kafka Connect's framework-recorded
offsets); capabilities as data with an explicit "unknown" (Kafka's three-valued
`exactlyOnceSupport`); deletes as a mode property with tombstones (Debezium, Airbyte CDC);
a separate sink with its own write modes diffed against the last sent image (Kafka sink
tasks, the reverse-ETL tools' mirror mode); a report that separates what the host refused
from what the remote rejected; and field mapping in host configuration, never connector
code (Kafka Connect's transforms).

Additive to the merged SPI, default methods throughout, pure JDK:

```java
public interface BacklogSource {                       // unchanged members first
    String id(); boolean available(); BacklogSnapshot read();
    default Capabilities capabilities() { return Capabilities.snapshotOnly(); }
    default ChangeBatch changes(Cursor since) { throw new UnsupportedOperationException(); }
    default Optional<BacklogSink> sink() { return Optional.empty(); }
    default void onCursorCommitted(Cursor committed) {}
}
record Capabilities(Set<SyncMode> syncModes,          // FULL_SNAPSHOT | INCREMENTAL | LOG_BASED
                    DeleteDetection deleteDetection,   // NONE | ON_SNAPSHOT_DIFF | IN_CHANGE_FEED
                    Set<WriteMode> writeModes,         // UPSERT | INSERT_ONLY | UPDATE_ONLY | MIRROR
                    Support exactlyOnce,               // YES | NO | UNKNOWN
                    boolean cursorIsOrdered, int spiVersion)
record Cursor(String sourceId, String opaque, Instant observedAt)   // host persists; hands back only the acknowledged one
sealed interface Change permits Change.Read, Change.Upserted, Change.Deleted
record ChangeBatch(List<Change> changes, Cursor cursorAfter, boolean complete)
interface BacklogSink { SyncReport write(List<Change> delta, WriteMode mode); }
record SyncReport(Status status, Counts counts, List<RowOutcome> outcomes, Cursor cursorAfter, List<String> degradations)
interface SyncConnector extends BacklogSource {}       // bidirectional = a source whose sink() is present
```

Rules: the snapshot captures its cursor before paginating; a change wins over an
overlapping snapshot row; applying one change twice is a no-op by key and version; `invalid`
is the host's gate before any remote call, `rejected` carries the remote's message. The
registry selects connectors by id as it already selects sources; the single-active
resolution and the no-op fallback are retired. For the `.vault/` connector the sink is the
writer of section 2, delete detection is by snapshot difference, and the cursor is the
commit SHA plus the working-tree digest.

## 6 · The agent surface sits inside the service, on the command layer

The MCP specification (revisions 2025-03-26, 2025-06-18, 2025-11-25, 2026-07-28) leaves
application authorisation to the server: tool annotations are untrusted hints, servers must
implement access control themselves, a server must not pass its token through to an
upstream API, and the tool list may vary by the authorisation on the request. Every hosted
tracker server surveyed (Linear, Atlassian, GitHub, Notion, Monday) acts as the authorising
user and bounds agents coarsely (read-only endpoints, scopes, tool groups off by default);
none documents sharing the UI's application layer, per-call idempotency, or what happens when
a human and an agent edit one field concurrently.

| Placement | Same guardrails as the UI | Identity and audit |
|---|---|---|
| (a) an adapter behind the connector contract | no: sync reconciles, it does not transition, so per-user workflow validation is bypassed by design | connector identity; neither human nor agent visible |
| (b) a separate server over the REST API | only if every rule lives behind the API and the UI is itself a pure client; needs its own upstream token and audience | the API sees the MCP server unless token exchange exists |
| (c) `@Tool` methods inside the service calling the same command services as the resources | yes, by construction: validators and permissions run once, after the identity is resolved and before persistence (the admission-controller and workflow-validator shape) | same OIDC audience; the command carries principal, actor, channel, reason, idempotency key |

**Decision: (c)**, with the Quarkiverse MCP server extension, which implements the current
revision and shares the ordinary HTTP authorisation policy, an identity-aware tool filter and
guardrails. The properties that make the parity structural: every state change is a command
in a module that depends on neither JAX-RS nor MCP, fenced by an architecture test; `/mcp/*`
under the same OIDC configuration and audience as REST; an agent acting for a user carries
the user's token and is recorded as actor, never principal (an agent cannot be held
accountable); an autonomous agent is its own OAuth client with its own user record; mutating
tools take an idempotency key honoured on every channel and the same `item.version` the UI
sends; a read-only server variant and a tool filter so destructive tools are never listed
without the scope; `confirm`, `dryRun` and `maxItems` on destructive and multi-item tools;
the trace context propagated so agent and human writes emit the same events. An agent's
transition on a file-backed item thus enters the write-back loop of section 2 and reaches the
files through the writer, never around the format's gate.

## 7 · Alternatives considered

- **Vectis as system of record, files as an export.** Rejected: inverts the format's
  authority rule and puts its gate on the wrong side; the documented two-store failure.
- **Newest-wins reconciliation between database and files.** Rejected: Beads' failure list.
- **Textual three-way merge of item files.** Rejected: hunk-level merges of adjacent YAML
  keys, moves read as delete plus add; the format's own rule is to regenerate.
- **Wall-clock last-writer-wins per field.** Rejected: the clock and ordering trouble every
  product that uses it documents; the only order is the backlog's commit sequence.
- **A Java reimplementation of the format's writer.** Rejected: a second writer of a format
  whose only specification is the first writer; promote the writer instead.
- **A branch and pull request per edit** (Decap, Tina). Rejected for a backlog that lands on
  its main branch by policy; retained as an option for repositories that review backlog
  changes.
- **Vectis-side key allocation with a seeded counter.** Rejected: two allocators of one
  sequence; the writer's serialised counter is the allocator.
- **Hash or random ids instead of sequential keys.** Rejected: the keys are the format's
  public, human-readable identity; the counter is serialised instead.
- **Watch-only binding without a timer.** Rejected: every documented event source is lossy
  (inotify overflow, macOS polling, at-least-once hooks, webhooks without redelivery).
- **Widening `SyncConnector` in place as the single active connector.** Rejected again
  (VEC-53): sources coexist; a no-op would have to invent an empty backlog.
- **A separate MCP server over the REST API.** Rejected: a second audience and deployable,
  audit degradation, and parity only by policy.

## 8 · Open for the PO

1. Ratify "projection": the record is the files, the tracker of record is the face.
2. Promote the format's writer to the public framework, or keep file-backed workspaces
   read-only.
3. First write-back scope: status, sprint, estimate; title and body after a field-setting
   command exists.
4. Conflicts: canonical-wins with a visible record and a manual queue, or stop-and-ask.
5. Intervals: 60 s ± 25 % (30 s locally).
6. Retire the single-active `SyncConnector`; redefine it as source plus sink.
7. The MCP surface in-process on the command layer (re-scopes the MCP backlog story).
8. The format's tooling: a field-setting command with a base-commit precondition, and a
   duplicate-renumbering repair that rewrites references.

## 9 · Two tests that tell whether the model is right

1. A full re-list at any commit regenerates identical projection rows and the second run
   appends zero events.
2. Three concurrent edits to one item, from the client, from an agent over MCP and from the
   writer, resolve by the same `version` check and the same retry-or-refuse rule, with the
   loser visible as a conflict record and never silently overwritten.

## 10 · Decisions taken (2026-10-21, PO)

1. **Projection ratified.** The files stay authoritative; Vectis is a rebuildable index, and "tracker of record" names the face.
2. **The format's writer is promoted to the public framework** and runs beside the service as a writer sidecar. Until it lands, file-backed workspaces stay read-only.
3. **First write-back scope accepted as an increment**: status, sprint and estimate first; title and body next. Nothing in the scope is final; it evolves.
4. **Conflicts are data hazards and get the canonical solutions, consistently.** Concurrent writers create read-after-write, write-after-write and write-after-read hazards exactly as overlapping instructions do, and the canonical answers (versioning, version checks as interlocks, a serialised commit order, forwarding of pending state to readers, replay of a command against the new base) apply the same way at every writer: the client, the agent surface and the writer itself. Consequence: a both-changed field is detected by version and resolved by refuse-and-replay, never by priority; the "canonical-wins" shortcut of section 2 is withdrawn, and every writer command takes a base precondition.
5. **Intervals accepted as proposed**, to be tuned on data.
6. **`SyncConnector` retired as a separate seam.** `BacklogSource` stays; a bidirectional connector is a source with an optional `sink()`, selected by id. The single-active resolution and the no-op fallback go.
7. **No agent surface until the API is authenticated and stable.** It must not be a way around the authenticated API; the in-process placement remains the shape for when the surface is ratified.
8. **No renumbering repair for now.** Prevention and detection already exist: the serialised committed counter is made the id policy of every space, this repository's `.vault` copy included, and the format's gate refuses a duplicate on every push. A duplicate can then only enter through a merge of two histories that each passed the gate alone; the repair is a documented manual step, and a tool is built only if that step is ever needed twice.
