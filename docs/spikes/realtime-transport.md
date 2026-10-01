# Real-time transport (spike) — draft ADR-04: real-time transport

| | |
|---|---|
| **Status** | Proposed |
| **Type** | Spike (Core tier), VEC-46 — written to be renamed `docs/adr/ADR-04-realtime-transport.md` |
| **Sprint** | VEC-S4 |
| **Requirement** | [ADR-01](../adr/ADR-01-product-requirements-and-features.md) **R-CORE-4** (state changes propagate over SSE, visible on every open board within 500 ms) |
| **Deliverable** | This decision + a throwaway two-instance prototype in [`realtime-transport/`](./realtime-transport/), a standalone Maven project outside the reactor. No production code, no migration on `main`. |
| **Reading** | The ADR is Context, Decision, Rules and Consequences. Findings §1 to §5 are the evidence; the appendices map the acceptance criteria, mark each claim measured or argued, and say how to reproduce. |
| **Unblocks** | VEC-17 (SSE workspace synchronization), and through it VEC-31 (estimation poker). Constrains VEC-35 (stream authentication). |

## Context

R-CORE-4:

> State changes propagate to all connected clients over SSE. *(target)* visible on every open board within 500 ms; remote updates are visually highlighted.

Staging runs `replicas: 2` (`deploy/k8s/overlays/staging/deployment-patch.yaml`). An SSE stream is one long-lived HTTP response held by one pod, so a broadcast kept in one JVM's memory reaches only the clients of the pod that handled the write. Nothing in the reactor carries an event between pods (no messaging, broker or notify dependency in any `pom.xml`), and nothing defines what an incremental change looks like on the wire: `web/src/store/types.ts` describes `WorkspaceSnapshot` as "a bulk payload as it would arrive from the reactive REST/SSE edge", and that is the only shape there is.

The question: **how does a state change on one server instance reach a browser connected to a different instance, and what does the event on the wire say?**

## Decision

1. **Carrier: PostgreSQL `LISTEN`/`NOTIFY` as a doorbell, over a short-retention event log written in the same transaction, with a catch-up poll underneath.** Every write takes the next per-workspace sequence number under the workspace row lock, appends the event to `workspace_event`, and calls `pg_notify('vectis_workspace', '<workspaceId>:<seq>')`, all in one transaction. Each instance holds one dedicated `LISTEN` connection and, when the doorbell rings for a workspace it has subscribers for, reads the new rows from the log and writes them to its streams. Every 2 s each instance also reads the head of every workspace it watches in one query and fetches any feed that is behind, so a `LISTEN` connection that has gone silently deaf costs at most one poll interval. No broker. No new third-party dependency: `PgSubscriber` ships in `vertx-pg-client`, which the reactor already has through `quarkus-reactive-pg-client`.
2. **Event contract: item events carry the item's full post-change state; configuration events are invalidations carrying the new revision.** One JSON envelope for all events (`type`, `workspaceId`, `seq`, `at`, `origin`). The SSE `event` is the `type`; the SSE `id` is `<epoch>.<seq>`, where the epoch identifies the workspace's stream across a database restore.
3. **Scope and delivery: one stream per workspace.** The client loads a snapshot, which says which `seq` it is current to, then opens `GET /api/v1/workspaces/{key}/events?after=<epoch>.<seq>`. On reconnect the browser sends `Last-Event-ID` by itself and the server replays the gap from the log. A cursor the log can no longer serve, or from another epoch, gets a `resync` event, and the client reloads the snapshot.
4. **Budget: the 500 ms target holds, measured.** From write on instance A to receipt by a client of instance B: p50 6.6 ms, p95 11.2 ms (one client); slowest of 200 clients per write p95 14.5 ms; under 100 writes/s, p95 6.8 ms per delivery and the worst single delivery of 400,000 was 94 ms. A deaf `LISTEN` connection degrades this to at most the poll interval plus one read (demonstrated: max 1.9 s at a 2 s poll).
5. **Convergence: a snapshot replaces the workspace's state; between snapshots, the highest `version` wins.** On the stream, events of a workspace cannot arrive out of order or with a gap (proved in §1). Out-of-order arrival is still possible *across* channels (my own write's HTTP response against an earlier event), and it was demonstrated; the version rule absorbs it. The precise client rules are below.

## Rules

These are the normative part of the decision; VEC-17 implements them.

**Write path.** One transaction per state change: take the next `seq` (`update workspace set event_seq = event_seq + 1 … returning`), apply the change with `item.version = item.version + 1`, insert the event row with the exact bytes that go on the wire, `pg_notify` the doorbell. Every writer locks the workspace row before any other row it writes, so writers cannot deadlock. Because the row lock is held until commit, a workspace's sequences are gap-free and commit in sequence order. Two refinements shorten the time the lock is held and are recommended to VEC-17 (not prototyped): take the `seq` as the last statement before the event insert, or do change, `seq`, event and `NOTIFY` in one statement (a data-modifying CTE), which leaves one or two round trips under the lock instead of four to six.

**Stream identity and cursor.** Each workspace has a `stream_epoch`, random, set when the workspace is created. The SSE `id` is `<epoch>.<seq>`, so `Last-Event-ID` carries both back. A database restore must regenerate every workspace's epoch in the same procedure (a runbook step: `update workspace set stream_epoch = <new random value>`); a restored database rewinds `seq`, and without a new epoch a client holding `seq` 95 against a restored head of 100 would be replayed 96 to 100 and silently miss the restored 91 to 95. The server answers `resync` when the cursor's epoch is not the workspace's current one, when the cursor is ahead of the head, when the log no longer holds the gap, or when the gap is over 10,000 events.

**Retention.** Prune by `seq`, never by time: per workspace, delete `seq < (min seq whose created_at >= cutoff)`. Invariant: **the retained events of a workspace form a contiguous suffix of its stream, ending at the head.** Pruning by `created_at` alone can punch a hole inside the retained range, because `created_at` and `at` are the transaction's *start* time and a later `seq` can carry an earlier time. For the same reason no one, server or client, orders events by `at`; order is `seq`, and per item, `version`.

**Liveness of the doorbell.** Three layers, so a silently dead `LISTEN` connection has a bound: (1) the catch-up poll above, every 2 s, one query per instance over all watched workspaces (prototyped and demonstrated); (2) a heartbeat, each instance notifying itself on a heartbeat channel every 10 s and forcing the subscriber to reconnect if none arrives within 30 s, which restores millisecond latency after the poll has taken over (argued, not prototyped); (3) TCP keep-alive and `TCP_USER_TIMEOUT` on the subscriber connection, so the socket itself dies within about a minute (set in the prototype; the idle, interval and user-timeout values take effect only on a native transport, unverified).

**Client convergence.**

1. A **snapshot** (first load, or after `resync`) **replaces** the workspace's item and configuration state wholesale: items the snapshot lacks are removed, and held versions are reset to the snapshot's even when lower (a restored database). It also sets the store's cursor to the snapshot's `<epoch>.<seq>` and clears the tombstones.
2. After a snapshot at `seq` S, any representation produced at or before S is dropped: stream events carry `seq`, and write responses carry the `seq` they produced (the `Vectis-Seq` header in the prototype). A late response to a write the snapshot already reflects can therefore not bring back an item the snapshot no longer has.
3. **Between snapshots**, an item representation (stream event or the client's own write response) is applied only if its `version` is above the version held for that item, or above its tombstone. `item.deleted` removes the item and records a tombstone `id → version`, so a late representation of a deleted item cannot resurrect it.
4. `configuration.changed` triggers a re-read of the configuration when its `revision` is above the held one.
5. On the stream, an event whose `seq` is not the last plus one is a protocol error: reconnect with the last cursor. `resync` means rule 1.

## Consequences

**Adopted, this means:**

- Real-time delivery is a property of the write path: every state change goes through one transaction helper that bumps `seq`, writes the change with a bumped `version`, appends the event and rings the doorbell. A change made any other way does not reach any board.
- No new infrastructure and no new third-party dependency. `vectis-server` gains a dependency on `vectis-persistence` (which it needs to serve any data anyway) and one `LISTEN` connection per replica.
- The schema gains `item.version`, `workspace.event_seq`, `workspace.stream_epoch` and `workspace_event`.
- The restore runbook gains a step: regenerate every workspace's `stream_epoch`.
- Clients hold one stream per open workspace and converge by the client rules: a snapshot, which carries the cursor it is current to, replaces their state; between snapshots the highest `version` or `revision` wins.
- Staging keeps `replicas: 2` and its rolling updates.

**Costs accepted.**

- **Per-workspace write ceiling.** The workspace row lock is held from the `seq` bump until commit. As prototyped that is four to six round trips: at the 0.06 ms of the demonstration network it is invisible, but at a managed PostgreSQL's 0.5 to 1 ms it is about 4 to 6 ms, a ceiling of roughly 150 to 250 writes per second **per workspace**. Human edit rates are far below it; the refinements under [Rules](#rules) (`seq` last, or one CTE) raise it several times.
- **Ancestor publish.** As prototyped, a template publish locks every descendant workspace `FOR UPDATE` for the whole fan-out, so a publish reaching many workspaces stalls all of their writes until it commits. VEC-71 should batch it per workspace: each workspace's revision change and event in its own short transaction, if VEC-66 does not require the publish to be atomic across workspaces.
- **Instance-wide `NOTIFY` serialisation.** A committing transaction that has notified holds a cluster-wide lock through its commit, including the WAL flush, so notifying commits across the whole PostgreSQL instance are capped at about one per commit latency: roughly 1,000 per second at a 1 ms flush, 200 to 500 at 2 to 5 ms on network storage. 100 notifying writes per second showed no contention here.
- One extra connection per replica, which must be direct or session-pooled: `LISTEN` does not survive PgBouncer transaction pooling and does not run on a read replica. Staging connects directly.
- An event table with an extra insert per write, a prune job, and one poll query per instance every 2 s.

**What would reopen it:** notifying commits on one PostgreSQL instance sustained above about half of 1 / commit latency, or measured waits on the notify lock; a deployment that must put PgBouncer in transaction mode in front of every connection with no direct path for one session per replica; events that must reach consumers other than browsers (integrations, analytics), which is when a broker reading this log as its outbox pays for itself; more than one database behind one workspace.

### VEC-17's remaining scope

With the transport decided, VEC-17 is high extension and low intension. Split by extension into three, in order:

1. **Enabler: the write path** (`vectis-persistence`): the migration for `item.version`, `workspace.event_seq`, `workspace.stream_epoch` and `workspace_event`; the one write-path helper, and every mutating repository method on it (item create, move, field edit, sprint assignment, delete; configuration delta, upgrade and ancestor publish as VEC-45, VEC-10 and VEC-71 land), with a test that each one appends an event; the snapshot read returning `<epoch>.<seq>`. It also gives VEC-16's item edits the `version` an `If-Match` needs. Nothing streams yet.
2. **Emit: the stream** (`vectis-server`): `GET /api/v1/workspaces/{key}/events` with `?after`, `Last-Event-ID`, the epoch check, `retry` jitter, keep-alive and `resync`; the `PgSubscriber` feed with the catch-up poll, the heartbeat and TCP keep-alive; the prune job by `seq`; replay outside the hub's lock; backpressure; metrics; the restore runbook step.
3. **Consume: the client** (`web`): one `EventSource` per open workspace opened at the snapshot's cursor; the reducer implementing the client convergence rules (snapshot replaces, `seq` floor, version rule, tombstones); re-reading configuration on a higher `revision`; the remote-change highlight driven by `origin` and `changed`.

Out of VEC-17, as before: stream authentication (VEC-35), estimation poker (VEC-31).

## Findings

The sections below are the evidence the decision rests on. They are not part of the ADR text.

## 1 · Fan-out: the two-instance demonstration

Setup, all containers on one Docker network, as pods share a cluster network: PostgreSQL 16.15 (the staging image, `postgres:16.15-alpine3.24`), instance **A** and instance **B** (the same jar, `spike.carrier` set per run), and the client. Three workspaces are seeded: `VEC` and `OPS` on the `scrum` template, `WEB` on `kanban`. Clients subscribe to `VEC` on A and on B, and to `OPS` and `WEB` on B. Then five writes go to **A**: move `VEC-1`, edit `VEC-2`, create `VEC-3`, change `VEC`'s configuration, publish version 2 of the `scrum` template.

**Without a carrier** (`spike.carrier=none`, each instance broadcasts after commit to its own streams; the developer note in VEC-17 describes this design):

```
--- client of A, workspace VEC, received:   id 1 item.moved, 2 item.updated, 3 item.created, 4 and 5 configuration.changed
--- client of B, workspace VEC, received:   (nothing)
--- client of B, workspace OPS, received:   (nothing)
probe: 50 writes to A, 1 client of B: delivered=0/50 missing=50
```

Nothing errors. B's clients hold an open, healthy stream that never carries anything written through A. With the staging Service spreading connections over two pods, about half of all boards would miss about half of all changes, silently.

**With the carrier** (`spike.carrier=pg`): the client of B received the same five frames as the client of A, byte for byte (shown in [§2](#2--event-contract)); the client watching `OPS` received its own `configuration.changed` for the template publish, with its own `seq` 1; the client watching `WEB` (kanban) received nothing. Every latency run below delivered 100 % of events.

### How the carrier works

Write path, one transaction, in this order on every writer, so writers cannot deadlock on each other:

1. `update workspace set event_seq = event_seq + 1 where … returning event_seq` takes the workspace row lock and the next `seq`;
2. the change itself, with `item.version = item.version + 1`;
3. `insert into workspace_event (workspace_id, seq, type, payload)`, the payload being the exact JSON that goes on the wire, stored as `text` so a replay is byte-identical;
4. `select pg_notify('vectis_workspace', '<workspaceId>:<seq>')`, about 40 bytes.

PostgreSQL delivers a `NOTIFY` only if its transaction commits, and delivers notifications of different transactions in commit order. The event row and the change commit together or not at all, so there is no dual write.

Each instance runs one `PgSubscriber` (a dedicated connection outside the pool) and, per workspace that has subscribers on that instance, a **feed** holding the highest `seq` it has read. A doorbell for a watched workspace makes the feed read `seq > head` from the log and hand the rows to its streams in order. A doorbell for an unwatched workspace costs nothing. Every 2 s, one query (`select id, event_seq from workspace where id = any($1)`) reads the heads of all watched workspaces, and any feed that is behind reads the log as if its doorbell had rung.

Why the feed never skips an event: `seq` is allocated under the workspace row lock, so the transaction that will commit `seq` n+1 cannot even allocate it before the one holding n has committed. Sequences are gap-free and commit in sequence order, so `seq > head` read at any instant returns a contiguous run. A new stream registers with the feed before it reads its replay, queues whatever the feed delivers meanwhile, and drops anything at or below the last `seq` it sent, so replay and live join without a gap or a duplicate.

### Reconnect semantics, demonstrated

**The client's instance dies.** A client of B received ids 1 and 2; B was killed (`docker kill`); three more writes went to A; the client reconnected to A (as the Service would route it) with `Last-Event-ID: 2` and received ids 3, 4 and 5 in order. Nothing was lost, and the client did not reload.

**The `LISTEN` session drops.** Both instances' `LISTEN` sessions were terminated with `pg_terminate_backend`, and five writes went to A at once. The events committed at 03:04:25.380 to .470; B's subscriber re-established at 03:04:25.620 (`LISTEN … established (#2)`) and its client received ids 1 to 5. The doorbells rung during the outage were lost, as `NOTIFY` is not durable; the feed read the log from its head on re-subscribe, so the outage cost 250 ms of latency and no events. This is the easy case: the connection was closed, so the subscriber noticed and reconnected.

**The `LISTEN` session goes deaf.** The hard case is a connection that stays open and receives nothing: a NAT or load-balancer idle timeout, or a failover that sends no reset. Without a safety net that instance hears no doorbell until the operating system gives up on the socket, which can take hours. B was made to ignore every doorbell (`POST /spike/deaf?on=true`, standing in for a half-open connection; nothing else changed), then 100 writes went to A at 10 per second: B's client received 100 of 100, p50 933 ms, p95 1,831 ms, max 1,930 ms. Every event arrived, carried by the 2 s catch-up poll; the bound is the poll interval plus one read.

### Carrier comparison

| | **PG doorbell + log (chosen)** | PG `NOTIFY` with the full event | Kafka (SmallRye Reactive Messaging) | Redis pub/sub | Pin to one replica |
|---|---|---|---|---|---|
| New reactor dependencies | none (`PgSubscriber` is in `vertx-pg-client`) | none | `quarkus-messaging-kafka`, `kafka-clients` | `quarkus-redis-client` | none |
| New infrastructure | none; one extra connection per replica | none | a Kafka cluster (three brokers for HA), topics, retention, monitoring | a Redis (with Sentinel for HA) | none |
| Payload limit | none (the event is a row; 9.5 KB delivered in the demo) | **8000 bytes**; a larger event fails *after* the write committed (demonstrated: `payload string too long`, event lost) | 1 MB default | no practical limit | n/a |
| Tied to the database commit | yes, `NOTIFY` is transactional | yes if sent in the transaction | **no**: publish after commit is a dual write; making it safe needs a transactional outbox and a relay (CDC or a poller), i.e. this log *plus* a broker | **no**, same dual write | n/a |
| Order | per workspace, commit order, gap-free | commit order | per partition, publish order | publish order | commit order within the one JVM only if delivery is synchronous |
| Reconnect and resume | a lost doorbell costs at most one poll interval (2 s); client resume replays from the log | lost notifications are lost; no replay | consumer offsets; a per-pod consumer group for fan-out, mapped from `Last-Event-ID` | lost; no replay (Streams would add replay) | the restart *is* the outage |
| Operational burden | a prune job; `LISTEN` needs a session-mode connection (no PgBouncer transaction pooling) | as left | a second stateful system to run, upgrade and back up | a second stateful system | Recreate rollouts with downtime |

**Why the doorbell rather than the full event in `NOTIFY`.** The full-payload variant saves one indexed read per event per watching instance, and pays for it with a hard 8000-byte limit (an item with a long description cannot be announced) and no replay. The log is needed for `Last-Event-ID` resume anyway, so the doorbell makes live delivery and resume one read path.

**Why not a broker.** A broker solves cross-process fan-out at a scale Vectis does not have, and does not solve the problem it does have: the event must commit with the change. Publishing to Kafka or Redis after commit is a dual write. [§5](#5--ordering-and-loss) shows what that does to a client: one stalled publisher, and a board shows the older title indefinitely. Making a broker safe needs exactly the log this design already has, plus a relay, plus the broker. A broker becomes right when the reopen conditions below are met, and the log is the outbox it would read from, so the migration path stays open.

**Pin to one replica, costed.** The staging patch would change `replicas: 2` to `replicas: 1` and add `strategy: {type: Recreate}` to the Deployment spec. Recreate is required, not optional: with the default `RollingUpdate` (here `maxSurge` 1, `maxUnavailable` 0) the old and the new pod serve together during every rollout, and the in-JVM broadcast splits clients exactly as with two replicas. With Recreate, every deploy is an outage of pod termination plus image start plus readiness (the readiness probe runs every 10 s), every client stream drops at once, and a crash is an outage; the patch's own comment says the second replica exists "so rolling updates stay available". Pinning also leaves the replay problem unsolved: a client that reconnects after the restart still needs the log. It is rejected; the doorbell costs less than its downtime.

The costs this design accepts are under [Consequences](#consequences).

## 2 · Event contract

**Decision: full entity for item events, invalidation for configuration events.**

- **Item events carry the item's full post-change state.** A patch only applies to the exact base version it was computed from, so one missed or reordered patch corrupts the client silently; a full representation is idempotent and commutes under "highest `version` wins", so duplicates, replays and reordering are all harmless. It is also the shape the REST read and the write response already return, so the client has one item reducer. An item is small: the largest realistic one is its description. `changed` names the properties that changed, for VEC-17's highlight; it is a hint, never applied.
- **Configuration events are invalidations carrying the new revision.** The effective configuration is a resolved document of several kilobytes (VEC-45), it changes rarely, a configuration change also re-projects the board's columns, and an ancestor publish fans out to every descendant workspace. The client re-reads `GET /api/v1/workspaces/{key}/configuration` (ETag = revision) when the event's `revision` is above the one it holds, and ignores it otherwise.
- **An invalidation of the whole workspace is `resync`.** It tells the client to reload the snapshot, which replaces its state, and continue the stream from the snapshot's cursor.

Envelope, the same for every event, keys in this order:

| Field | Meaning |
|---|---|
| `type` | `item.created`, `item.updated`, `item.moved`, `item.deleted`, `configuration.changed`, `resync`. Also the SSE `event` field. A new type is additive; clients ignore types they do not know. A breaking change goes to a new API version. |
| `workspaceId` | The workspace's id, not its key: keys can be re-keyed live (R-CORE-6). |
| `seq` | Position in the workspace's stream: gap-free, increasing, commit order. The SSE `id` is `<epoch>.<seq>` (see [Rules](#rules)), so `Last-Event-ID` carries it back; the prototype's frames below show the bare `seq`. |
| `at` | Transaction start time of the change, UTC, microseconds. Display only: it is not monotonic in `seq`, and nothing orders by it. |
| `origin` | The writer's `Vectis-Origin` request header (an opaque per-tab tag, `[A-Za-z0-9._-]{1,64}`, else `null`), so a tab can tell its own writes from remote ones for the highlight. It is not an identity; the authenticated actor belongs to VEC-35. |

Each stream opens with a comment naming the workspace and cursor, and a `retry` chosen per stream between 1000 and 3000 ms so the clients of a pod that dies do not all reconnect in the same instant. Then the events. These are the exact bytes the client of instance B received in the demonstration, each frame ending in a blank line:

```
:workspace VEC after 0
retry:2853

```

**Item moved** (written through A with `Vectis-Origin: tab-7f3a`):

```
id:1
event:item.moved
data:{"type":"item.moved","workspaceId":"01a0f568-ff3d-7f3a-abbe-1c614150e04b","seq":1,"at":"2026-10-01T03:01:35.292244Z","origin":"tab-7f3a","item":{"id":"01a0f568-ff5d-70d3-bb78-12a9a9725752","key":"VEC-1","boardId":"01a0f568-ff3d-7cfa-bd5e-6aea3e0388b3","columnId":"01a0f568-ff3d-77f3-badd-1ede76c6cda2","title":"Stream workspace events over SSE","rank":"k","fields":{"type":"story","storyPoints":5},"sprintId":null,"version":2,"updatedAt":"2026-10-01T03:01:35.292244Z"},"changed":["columnId","rank"]}

```

**Item field edited** (title and one open field):

```
id:2
event:item.updated
data:{"type":"item.updated","workspaceId":"01a0f568-ff3d-7f3a-abbe-1c614150e04b","seq":2,"at":"2026-10-01T03:01:35.338368Z","origin":null,"item":{"id":"01a0f568-ff5d-7231-80e7-b7c8d8e7cd3e","key":"VEC-2","boardId":"01a0f568-ff3d-7cfa-bd5e-6aea3e0388b3","columnId":"01a0f568-ff3d-7097-baec-433d7095ed48","title":"Highlight remote updates on every board","rank":"p","fields":{"type":"story","storyPoints":5},"sprintId":null,"version":2,"updatedAt":"2026-10-01T03:01:35.338368Z"},"changed":["title","fields.storyPoints"]}

```

**Item created:**

```
id:3
event:item.created
data:{"type":"item.created","workspaceId":"01a0f568-ff3d-7f3a-abbe-1c614150e04b","seq":3,"at":"2026-10-01T03:01:35.385916Z","origin":null,"item":{"id":"01a0f569-041b-7a65-baeb-96b7479de85f","key":"VEC-3","boardId":"01a0f568-ff3d-7cfa-bd5e-6aea3e0388b3","columnId":"01a0f568-ff3d-7097-baec-433d7095ed48","title":"Resume a stream from Last-Event-ID","rank":"t","fields":{"type":"task"},"sprintId":null,"version":1,"updatedAt":"2026-10-01T03:01:35.385916Z"},"changed":["key","boardId","columnId","title","rank","fields"]}

```

**Configuration changed by the workspace's own delta write** (VEC-45's `PUT …/configuration/delta`; an upgrade carries `{"kind":"upgrade","template":"scrum","fromVersion":1,"toVersion":2}`):

```
id:4
event:configuration.changed
data:{"type":"configuration.changed","workspaceId":"01a0f568-ff3d-7f3a-abbe-1c614150e04b","seq":4,"at":"2026-10-01T03:01:35.416565Z","origin":null,"revision":1,"cause":{"kind":"delta"}}

```

**Configuration changed because an ancestor template level published a version the workspace inherits.** One publish, one transaction, one event per affected workspace, each in its own stream with its own `seq` and its own new `revision`. `VEC`'s stream:

```
id:5
event:configuration.changed
data:{"type":"configuration.changed","workspaceId":"01a0f568-ff3d-7f3a-abbe-1c614150e04b","seq":5,"at":"2026-10-01T03:01:35.439936Z","origin":null,"revision":2,"cause":{"kind":"ancestor","template":"scrum","version":2}}

```

`OPS`'s stream, from the same transaction (`WEB`, on `kanban`, received nothing):

```
id:1
event:configuration.changed
data:{"type":"configuration.changed","workspaceId":"01a0f568-ff3d-7b3b-80bd-f224bf999380","seq":1,"at":"2026-10-01T03:01:35.439936Z","origin":null,"revision":1,"cause":{"kind":"ancestor","template":"scrum","version":2}}

```

**Resync** (here a cursor ahead of the stream, `Last-Event-ID: 999`, which `demo.sh` section 2 sends to B; `reason` is `retention` when the log no longer holds the gap, `bulk` after a bulk write):

```
id:1
event:resync
data:{"type":"resync","workspaceId":"01a0f56c-022d-7673-bb6d-553c23706d15","seq":1,"at":"2026-10-01T03:04:51.688307Z","origin":null,"reason":"unknown-cursor"}

```

**Item deleted** (specified, not prototyped): the envelope plus `"item":{"id":…,"key":…,"version":n}`, a tombstone the client applies under the same version rule.

### The configuration-changed event and the template hierarchy

The event fires **in the transaction that changes a workspace's effective configuration, whatever the cause**: the workspace's own delta write, an upgrade that re-pins it, or a publish at an ancestor template level that the workspace inherits. An ancestor publish therefore emits one event per affected workspace, in the publishing transaction, locking the workspaces in id order. Whether a publish changes its descendants at once or only at their next upgrade is VEC-66's decision; the contract does not depend on it, only `cause` does. VEC-71's publish path calls the same write-path helper. A publish that reaches very many workspaces is still one doorbell per workspace, each about 40 bytes, and the fan-out cost is per *watched* workspace on each instance.

### Bulk writes

A write that changes many items at once (the import path, `ItemRepository.insertAll`) appends one `resync` event with `reason: "bulk"` instead of one event per row: watching clients reload once.

## 3 · Scoping and delivery

**Subscribe per workspace, filter boards on the client.** `GET /api/v1/workspaces/{key}/events`. The stream's `seq` space is the workspace's, which is what makes a resume cursor a single integer and a gap detectable. A per-board stream would need its own sequence per board or would show gaps the client cannot tell from loss; a board is a view over the workspace, every item event carries `boardId`, and a workspace's event rate is human-scale. A client with several workspaces open holds one stream per workspace.

**First connect.** Load the snapshot (`GET /api/v1/workspaces/{key}/snapshot` in the prototype; in VEC-17 whichever read builds the board), which returns the `seq` it is current to, read in the same statement and so the same snapshot as the items. Open the stream with `?after=<seq>`: `EventSource` cannot set a header on its first request. Without a cursor the stream starts live, at the current head.

**Reconnect.** The browser's `EventSource` reconnects by itself after `retry` and sends the last `id` it saw as `Last-Event-ID`; the header wins over `?after`. The server replays `seq > cursor` from the log, then continues live. The answer is `resync` when the cursor is ahead of the head (a restored database), older than the oldest retained event, or more than 10,000 events behind. The client never re-hydrates wholesale unless told to.

**Retention.** Proposed: keep 24 hours of events per workspace, pruned by `seq` as stated under [Rules](#rules), so the retained events stay a contiguous suffix; a client away longer gets `resync`. The number only trades table size against how often a laptop waking up reloads, and can be tuned without a contract change.

**Database restore.** A restore rewinds every workspace's `seq`. The restore procedure regenerates each workspace's `stream_epoch`; every open cursor then carries a stale epoch, and every client gets `resync` and reloads.

**Keep-alive.** A comment frame (`:keep-alive`) every 20 s keeps proxies and load balancers from closing an idle stream and finds dead connections.

**Constraints this places on others.**

- **VEC-35 (authentication).** `EventSource` cannot send an `Authorization` header, so the stream must authenticate by a same-origin session cookie or by a short-lived ticket in the query string (which then appears in access logs). Authorization is checked once, at subscribe, per workspace; a stream outlives a permission change, so revoking access must close the user's open streams. The doorbell carries only ids, and event bodies are read per watched workspace, so nothing reaches an instance's streams that the subscribe check did not admit.
- **Deployment.** The ingress must serve HTTP/2 to browsers (HTTP/1.1 allows six connections per origin, which one stream per open workspace exhausts quickly), must not buffer `text/event-stream`, and must allow idle streams longer than the 20 s keep-alive. Not verified in this spike.
- **VEC-14 (tenancy).** One channel serves every tenant; isolation is the subscribe check, as above.

## 4 · Budget

**Method.** A JDK-only client (`probe/Probe.java`, `latency` mode) runs in its own container on the cluster network. It opens K SSE streams, after a snapshot, to the watched instance(s), then sends paced `PATCH` requests that set `fields.probe = i` on an item, through instance A (or alternately A and B). For each write it takes `System.nanoTime()` immediately before sending the request; each stream takes `System.nanoTime()` when it reads the blank line that ends the frame carrying probe `i`. One process, one clock. Latency = receipt − send, so it includes the HTTP request to A, the six statements of the write transaction, commit, the `NOTIFY` reaching B's `LISTEN` session, B's read of the log, the SSE write and the client's read. "Per write, slowest client" takes, for each write, the latest receipt over all K clients: that is the R-CORE-4 quantity, *every* open board.

**Environment.** Apple M3 Pro, 11 cores, 18 GB; macOS; Docker Desktop 29.8.1 (VM with 11 vCPUs, 8 GB); PostgreSQL 16.15 (`postgres:16.15-alpine3.24`, the staging image, default settings); two instances of the prototype on Quarkus 3.37.1 in JVM mode on Temurin 25.0.4 (`eclipse-temurin:25-jdk`); the client in a third JDK container; all on one Docker bridge network, database round trip 0.06 ms (`pgbench`, `select 1`). Each run follows the previous ones on the same JVMs, so they are warm.

**Results** (`demo.sh all`, section 3):

| Run | Deliveries | Per delivery p50 / p95 / p99 / max (ms) | Per write, slowest client p50 / p95 / p99 / max (ms) | Write HTTP round trip p50 / p95 (ms) |
|---|---|---|---|---|
| 1000 writes to A at 20/s, 1 client of B | 1000/1000 | 6.56 / 11.16 / 16.25 / 25.75 | same | 5.90 / 10.32 |
| 1000 writes to A at 20/s, 200 clients split over A and B | 200,000/200,000 | 6.51 / 12.30 / 17.49 / 35.82 | 8.38 / 14.52 / 20.02 / 35.82 | 4.15 / 8.22 |
| 2000 writes to A and B at 100/s from 4 writers, 200 clients split over A and B | 400,000/400,000 | 3.54 / 6.79 / 17.02 / 93.70 | 4.88 / 8.47 / 24.77 / 93.70 | 1.75 / 4.18 |

The full sequence was run six times; the latest run, on the final code, agreed: per write, slowest client p95 7.84, 11.94 and 9.23 ms for the three runs, worst single delivery 109 ms.

**Verdict: the 500 ms target in R-CORE-4 holds**, with a margin of more than thirty times at p95 for the slowest of 200 boards. The carrier's own share is the difference between delivery and the write's own round trip, about 0.7 to 1.8 ms at the median. The lower write rate shows *higher* latency than the higher one, because between paced requests the Docker Desktop VM's threads go idle and every wake-up costs; that inflates the numbers, it does not flatter them.

**Not measured here:** the network between a browser and the ingress, the ingress itself, pods on different nodes, the native image staging actually runs, and browser rendering. None of them is plausibly hundreds of milliseconds in steady state, which is why the number can stay.

**Proposal: keep 500 ms, and say what it covers.** "p95 ≤ 500 ms from commit to receipt at every connected client, in steady state. After a dropped stream, every change is delivered, in order, within the reconnect delay (1 to 3 s) plus 500 ms." A pod restart or a network change is not steady state, and the design promises no loss there rather than a latency.

## 5 · Ordering and loss

**Convergence rule.** Stated in full under [Rules](#rules): a snapshot replaces the state; afterwards representations at or below the snapshot's `seq` are dropped, and an item representation applies only if its `version` is above the held version or tombstone. Applying the version rule to snapshot rows as well would not converge: an item deleted while the client was away past retention would never be removed, and after a database restore the true, lower versions would be rejected. The rule needs **`item.version`, which does not exist**: today `ItemRepository.move` and `moveToSprint` write blind (`update item set … where id = $3`). It is also what an `If-Match` on item edits (VEC-16's CRUD) would need, as `config_revision` is for configuration.

**Can two changes to one item arrive out of order?** Not on the stream: one workspace's events are gap-free, commit-ordered, read in `seq` order and sent in `seq` order. Across channels, yes. Two demonstrations:

**A: publishing after commit (a broker-shaped dual write).** `spike.carrier=pg-postcommit`: the full event is published on a channel *after* the transaction commits, as any producer outside the database transaction does. A's publisher is stalled 300 ms (a GC pause, a slow broker). The title of `VEC-1` is written to `first` through A, then 50 ms later to `second` through B:

```
arrival order at a client of B:
  + 158.6 ms  seq=2 version=3 title=second
  + 367.0 ms  seq=1 version=2 title=first
database:                       title=second
client, last arrival wins:      title=first   <- diverged
client, highest version wins:   title=second
```

Under last-arrival-wins the board shows `first` until something else changes that item or the page reloads; nothing tells the user. Under the version rule it converges. The same run showed the other half of the post-commit hazard: a 9,000-character description made the publish fail (`ERROR: payload string too long`) after the write had committed, and the event was lost. The chosen design has neither problem; the run is why "any carrier, publish after commit" is not an acceptable shortcut.

**B: my own write against a concurrent remote write, in the chosen design.** A colleague's write goes to A; 0 to 3 ms later, before theirs has answered, my write to the same item goes to B; my stream is on B. 300 trials; the client merges stream events and its own responses in arrival order:

```
no stall:                     arrival order went back to an older version 0 times in 900 representations (an earlier run: 1)
B's feed stalled 20 ms:       arrival order went back 520 times; it showed the older version for p50 12.5 ms, max 25.3 ms
version rule, both runs:      went back 0 times; final version 601 = database; dropped stale and echoed representations
```

The 20 ms stall stands in for a GC pause or a slow read on the instance holding my stream. Under last-arrival-wins, the card I just edited flicks back to the colleague's older value and then forward again, a highlighted remote change that never happened. The version rule drops it, and it also drops the echo of my own write arriving on the stream after my response, so my own edits are not highlighted as remote.

**Loss.** None in the chosen path, under two conditions: an event exists if and only if its change committed, the log holds it, every reconnect (the client's stream or the instance's `LISTEN` session) resumes from a position in the log, and a deaf `LISTEN` connection is covered by the poll. Beyond retention the client is told to reload. The two conditions: retention is pruned by `seq`, so the retained log has no hole, and a database restore regenerates the stream epochs; without that runbook step a client can be left with a silent gap.

## Appendix A · Acceptance criteria — how each is met

| AC | Where it is met |
|----|-----------------|
| **1. Findings document answering the five sub-questions with a decision** | This document. The path is `docs/spikes/realtime-transport.md`, following the repository's neutral naming (`plugin-loading.md`, `template-instance-model.md`), not the `VEC-46-…` path the item predates the convention with. |
| **2. Running two-instance demonstration, without and with the carrier** | [§1](#1--fan-out-the-two-instance-demonstration): two real Quarkus instances on one PostgreSQL. Without a carrier, B's client received 0 of 5 events and 0 of 50 probes. With the carrier, every event, byte-identical on both instances. Reproduce with `docs/spikes/realtime-transport/demo.sh all`. |
| **3. Carrier named with its cost: dependencies, infrastructure, reconnect** | [§1 Carrier comparison](#carrier-comparison) against Kafka, Redis and pinning to one replica, including what pinning would require of the staging patch. Reconnect demonstrated three times (client instance killed; `LISTEN` session terminated; `LISTEN` session deaf, caught by the poll). |
| **4. Three payloads in full, bytes on the wire, with the decision and its reason** | [§2](#2--event-contract): `item.moved`, `item.updated`, `item.created`, plus `configuration.changed` (own delta, and an ancestor publish fanned out to two workspaces) and `resync`, copied from the stream of the prototype. |
| **5. Measured p50/p95, method, environment, verdict on 500 ms** | [§4](#4--budget): three runs, method and environment recorded. 500 ms holds; the proposal is to keep the number and say what it means during a reconnect. |
| **6. Convergence rule and a demonstrated out-of-order case** | [§5](#5--ordering-and-loss): two demonstrations, a broker-shaped publish after commit that leaves a client permanently wrong under last-arrival-wins, and the own-write race in the recommended design. |
| **7. Liftable into ADR-04 as Proposed, with VEC-17's remaining scope** | The ADR is the top of this document: header, Context, [Decision](#decision), [Rules](#rules), [Consequences](#consequences) and [VEC-17's remaining scope](#vec-17s-remaining-scope). Findings and appendices are the evidence and stay behind. |
| **8. Root `mvn -B -ntp verify` unaffected, nothing in `vectis-server/src/main`** | The prototype is its own Maven project under `docs/spikes/realtime-transport/`, not a reactor module and not a child of `vectis-parent`. |

## Appendix B · Evidence: measured or argued

| Claim | Basis |
|---|---|
| Without a carrier, clients of the other instance receive nothing | **Measured**: two instances, 0/5 events and 0/50 probes on B (§1) |
| The doorbell carrier delivers every event to both instances, byte-identical, and fans an ancestor publish out per workspace | **Measured**: §1, §2; 100 % delivery in every latency run |
| p50/p95 latency and the 500 ms verdict | **Measured** in the environment of §4; browser network, ingress, cross-node pods and the native image are **not** measured |
| Resume with `Last-Event-ID` after the client's instance dies | **Demonstrated** once (§1); the retention and `resync` rules are prototyped but nothing prunes, so retention behaviour is **argued** |
| A `LISTEN` session closed cleanly loses no events | **Demonstrated** once (§1), by `pg_terminate_backend`: the subscriber sees the close and reconnects |
| A silently lost or half-open `LISTEN` session loses no events | Not demonstrated by a real half-open socket; covered by the catch-up poll in the next row |
| A deaf `LISTEN` session is bounded by the 2 s catch-up poll | **Demonstrated** once (§1), with the deafness **simulated** by ignoring doorbells on B, not by a real half-open socket |
| The heartbeat that forces a reconnect, and the TCP keep-alive and user-timeout settings | **Argued**; keep-alive is set in the prototype but its idle, interval and user-timeout values need a native transport, unverified |
| Stream epoch across a database restore | **Argued**; not prototyped (the prototype's ids are bare `seq`) |
| Retention pruned by `seq` keeps a contiguous suffix | **Argued**; the prototype has no prune job |
| Snapshot-replaces, `seq` floor and tombstone client rules | **Argued**; the client reducer is VEC-17's, the probe applies only the version rule |
| Per-workspace write ceiling and the `NOTIFY` commit cap | **Argued**, computed from round trips and commit latency; not measured on managed PostgreSQL |
| Publishing after commit can leave a client wrong, and loses events over 8000 bytes | **Demonstrated** with an injected 300 ms stall (§5) |
| Cross-channel reordering in the chosen design, absorbed by the version rule | **Measured**: 0–1 of 900 representations (300 trials) went back naturally, 504–537 of 900 with an injected 20 ms stall (§5) |
| A workspace's stream is gap-free and in commit order | **Argued** from lock ordering (§1); consistent with every run (no gap, no reorder seen on the stream), but not a proof by test |
| Headroom of the per-workspace lock | **Measured** only at 100 writes/s against a 0.06 ms database round trip; the ceiling at managed-database latency is **computed** (Consequences) |
| "The carrier adds about 1 ms" | **Derived** from two measured numbers: delivery latency minus the write's own HTTP round trip (§4) |
| `item.deleted` and bulk `resync` | **Specified**, not built |
| `PgSubscriber` in the native image staging runs | **Unverified**; every run here is JVM mode |
| Kafka and Redis costs, dependencies, limits and reconnect behaviour | **Argued** from their documented properties; no broker was run |
| Pin-to-one-replica rollout costs | **Argued** from the manifests; not deployed |
| Constraints on VEC-35 and the ingress (HTTP/2, no buffering) | **Argued**; not verified |

## Appendix C · How to run

```bash
cd docs/spikes/realtime-transport
./demo.sh all       # builds, runs sections 1-9 recorded above, removes its containers
./demo.sh up pg pg  # or: keep two instances running on 127.0.0.1:5741 (A) and :5742 (B)
curl -s -X POST 127.0.0.1:5741/spike/reset
curl -sN '127.0.0.1:5742/api/v1/workspaces/VEC/events?after=0' &
curl -s -X PATCH 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-1 -H 'Content-Type: application/json' -d '{"title":"hello from A"}'
./demo.sh down
```

Requires Docker and a Maven repository that can resolve the Quarkus 3.37.1 BOM (the root build already does). The containers are named `vec46-pg`, `vec46-a` and `vec46-b` on the network `vec46-net`.

## Appendix D · Prototype quality vs. what needs hardening

**Prototype quality (as shipped):** schema created at startup, not by Flyway; a cut-down `workspace` and `item`; columns are seeded ids rather than VEC-45's projected `board_column` rows; configuration writes and template publishes only bump revisions; no authentication; no retention job (the `resync`-on-retention logic is there, nothing prunes); the hub is one monitor per instance, and a replay runs under it; stream ids are bare `seq`, without the epoch; no heartbeat; `item.deleted` and bulk `resync` are specified, not built; no tests, because the demonstration is the test.

**Would need hardening in VEC-17:** Flyway migration for `item.version`, `workspace.event_seq`, `workspace.stream_epoch` and `workspace_event`, with its prune job by `seq`; the `<epoch>.<seq>` cursor and the restore runbook step; the heartbeat with a reconnect deadline, alongside the catch-up poll and TCP keep-alive; replays read and sent outside the hub's lock, so a reconnect storm (every client of a dead pod replaying up to 10,001 rows at once) cannot block the live delivery of every other stream; the shorter lock hold (`seq` last, or one CTE) and a per-workspace batched ancestor publish; every write path through the one seq-bumping helper (a write that forgets it is invisible to every board, so a test should assert that each mutating repository method appends an event); backpressure for a slow client (bounded per-stream queue, then close it and let it resume); metrics for open streams, feed lag and `LISTEN` reconnects; native-image verification of `PgSubscriber` (expected to work, unverified); the ingress settings in §3.

## Appendix E · Premises re-checked on today's `main`

- **Still true:** staging runs `replicas: 2`; no `pom.xml` in the reactor names messaging, Kafka, AMQP or Redis; `vectis-server` holds one resource, `ExtensionDiagnosticsResource`; `WorkspaceSnapshot` is the only payload shape; ADR-04 is unwritten (`docs/adr/` holds ADR-01 only, and VEC-45's draft is ADR-03).
- **No longer true:** "there is no write path yet from which an event could be emitted". `vectis-persistence` now has reactive write methods (`ItemRepository.insert`, `insertAll`, `move`, `moveToSprint`; sprint, board and workspace repositories). No REST endpoint calls them yet, and `vectis-server` does not depend on `vectis-persistence`.
- **Missing, and needed by this design:** `item.version`. Item updates today are blind writes.
- **Rejected:** VEC-17's developer note ("use Quarkus Reactive Messaging to broadcast update details") describes the in-JVM broadcast of §1, which leaves the clients of every other replica stale.
