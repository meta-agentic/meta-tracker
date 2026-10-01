# Real-time transport (spike) — draft ADR-04: real-time transport

| | |
|---|---|
| **Status** | Proposed |
| **Type** | Spike (Core tier), VEC-46 — written to be renamed `docs/adr/ADR-04-realtime-transport.md` |
| **Sprint** | VEC-S4 |
| **Requirement** | [ADR-01](../adr/ADR-01-product-requirements-and-features.md) **R-CORE-4** (state changes propagate over SSE, visible on every open board within 500 ms) |
| **Deliverable** | This decision + a throwaway two-instance prototype in [`realtime-transport/`](./realtime-transport/), a standalone Maven project outside the reactor. No production code, no migration on `main`. |
| **Unblocks** | VEC-17 (SSE workspace synchronization), and through it VEC-31 (estimation poker). Constrains VEC-35 (stream authentication). |

## Context

R-CORE-4:

> State changes propagate to all connected clients over SSE. *(target)* visible on every open board within 500 ms; remote updates are visually highlighted.

Staging runs `replicas: 2` (`deploy/k8s/overlays/staging/deployment-patch.yaml`). An SSE stream is one long-lived HTTP response held by one pod, so a broadcast kept in one JVM's memory reaches only the clients of the pod that handled the write. Nothing in the reactor carries an event between pods (no messaging, broker or notify dependency in any `pom.xml`), and nothing defines what an incremental change looks like on the wire: `web/src/store/types.ts` describes `WorkspaceSnapshot` as "a bulk payload as it would arrive from the reactive REST/SSE edge", and that is the only shape there is.

The question: **how does a state change on one server instance reach a browser connected to a different instance, and what does the event on the wire say?**

## Decision

1. **Carrier: PostgreSQL `LISTEN`/`NOTIFY` as a doorbell, over a short-retention event log written in the same transaction.** Every write takes the next per-workspace sequence number under the workspace row lock, appends the event to `workspace_event`, and calls `pg_notify('vectis_workspace', '<workspaceId>:<seq>')`, all in one transaction. Each instance holds one dedicated `LISTEN` connection and, when the doorbell rings for a workspace it has subscribers for, reads the new rows from the log and writes them to its streams. No broker. No new third-party dependency: `PgSubscriber` ships in `vertx-pg-client`, which the reactor already has through `quarkus-reactive-pg-client`.
2. **Event contract: item events carry the item's full post-change state; configuration events are invalidations carrying the new revision.** One JSON envelope for all events (`type`, `workspaceId`, `seq`, `at`, `origin`). The SSE `id` is the `seq`, the SSE `event` is the `type`.
3. **Scope and delivery: one stream per workspace.** The client loads a snapshot, which says which `seq` it is current to, then opens `GET /api/v1/workspaces/{key}/events?after=<seq>`. On reconnect the browser sends `Last-Event-ID` by itself and the server replays the gap from the log. A cursor the log can no longer serve gets a `resync` event, and the client reloads the snapshot.
4. **Budget: the 500 ms target holds, measured.** From write on instance A to receipt by a client of instance B: p50 6.6 ms, p95 11.2 ms (one client); slowest of 200 clients per write p95 14.5 ms; under 100 writes/s, p95 6.8 ms per delivery and the worst single delivery of 400,000 was 94 ms. The carrier adds about 1 ms over the write's own HTTP round trip.
5. **Ordering and loss: the store applies an item representation only if its `version` is higher than the one it holds.** On the stream, events of a workspace cannot arrive out of order or with a gap (proved below). Out-of-order arrival is still possible *across* channels (my own write's HTTP response against an earlier event, a snapshot against the stream), and it was demonstrated; the version rule absorbs it. Lost doorbells cost latency, never events: the log is read from the feed's position after every reconnect.

## Evidence: measured or argued

| Claim | Basis |
|---|---|
| Without a carrier, clients of the other instance receive nothing | **Measured**: two instances, 0/5 events and 0/50 probes on B (§1) |
| The doorbell carrier delivers every event to both instances, byte-identical, and fans an ancestor publish out per workspace | **Measured**: §1, §2; 100 % delivery in every latency run |
| p50/p95 latency and the 500 ms verdict | **Measured** in the environment of §4; browser network, ingress, cross-node pods and the native image are **not** measured |
| Resume with `Last-Event-ID` after the client's instance dies | **Demonstrated** once (§1); the retention and `resync` rules are prototyped but nothing prunes, so retention behaviour is **argued** |
| A dropped `LISTEN` session loses no events | **Demonstrated** once (§1) |
| Publishing after commit can leave a client wrong, and loses events over 8000 bytes | **Demonstrated** with an injected 300 ms stall (§5) |
| Cross-channel reordering in the chosen design, absorbed by the version rule | **Measured**: 0–1 of 300 trials naturally, 520 with an injected 20 ms stall (§5) |
| Kafka and Redis costs, dependencies, limits and reconnect behaviour | **Argued** from their documented properties; no broker was run |
| Pin-to-one-replica rollout costs | **Argued** from the manifests; not deployed |
| `NOTIFY` commit-lock contention as the scaling ceiling | **Argued** from PostgreSQL's documented behaviour; 100 notifying writes/s showed none |
| Constraints on VEC-35 and the ingress (HTTP/2, no buffering) | **Argued**; not verified |

## Acceptance criteria — how each is met

| AC | Where it is met |
|----|-----------------|
| **1. Findings document answering the five sub-questions with a decision** | This document. The path is `docs/spikes/realtime-transport.md`, following the repository's neutral naming (`plugin-loading.md`, `template-instance-model.md`), not the `VEC-46-…` path the item predates the convention with. |
| **2. Running two-instance demonstration, without and with the carrier** | [§1](#1--fan-out-the-two-instance-demonstration): two real Quarkus instances on one PostgreSQL. Without a carrier, B's client received 0 of 5 events and 0 of 50 probes. With the carrier, every event, byte-identical on both instances. Reproduce with `docs/spikes/realtime-transport/demo.sh all`. |
| **3. Carrier named with its cost: dependencies, infrastructure, reconnect** | [§1 Carrier comparison](#carrier-comparison) against Kafka, Redis and pinning to one replica, including what pinning would require of the staging patch. Reconnect demonstrated twice (client instance killed; `LISTEN` session terminated). |
| **4. Three payloads in full, bytes on the wire, with the decision and its reason** | [§2](#2--event-contract): `item.moved`, `item.updated`, `item.created`, plus `configuration.changed` (own delta, and an ancestor publish fanned out to two workspaces) and `resync`, copied from the stream of the prototype. |
| **5. Measured p50/p95, method, environment, verdict on 500 ms** | [§4](#4--budget): three runs, method and environment recorded. 500 ms holds; the proposal is to keep the number and say what it means during a reconnect. |
| **6. Convergence rule and a demonstrated out-of-order case** | [§5](#5--ordering-and-loss): two demonstrations, a broker-shaped publish after commit that leaves a client permanently wrong under last-arrival-wins, and the own-write race in the recommended design. |
| **7. Liftable into ADR-04 as Proposed, with VEC-17's remaining scope** | The header and [Consequences](#consequences) are written as the ADR; [VEC-17's remaining scope](#vec-17s-remaining-scope) is listed. |
| **8. Root `mvn -B -ntp verify` unaffected, nothing in `vectis-server/src/main`** | The prototype is its own Maven project under `docs/spikes/realtime-transport/`, not a reactor module and not a child of `vectis-parent`. |

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

Each instance runs one `PgSubscriber` (a dedicated connection outside the pool) and, per workspace that has subscribers on that instance, a **feed** holding the highest `seq` it has read. A doorbell for a watched workspace makes the feed read `seq > head` from the log and hand the rows to its streams in order. A doorbell for an unwatched workspace costs nothing.

Why the feed never skips an event: `seq` is allocated under the workspace row lock, so the transaction that will commit `seq` n+1 cannot even allocate it before the one holding n has committed. Sequences are gap-free and commit in sequence order, so `seq > head` read at any instant returns a contiguous run. A new stream registers with the feed before it reads its replay, queues whatever the feed delivers meanwhile, and drops anything at or below the last `seq` it sent, so replay and live join without a gap or a duplicate.

### Reconnect semantics, demonstrated

**The client's instance dies.** A client of B received ids 1 and 2; B was killed (`docker kill`); three more writes went to A; the client reconnected to A (as the Service would route it) with `Last-Event-ID: 2` and received ids 3, 4 and 5 in order. Nothing was lost, and the client did not reload.

**The `LISTEN` session drops.** Both instances' `LISTEN` sessions were terminated with `pg_terminate_backend`, and five writes went to A at once. The events committed at 03:04:25.380 to .470; B's subscriber re-established at 03:04:25.620 (`LISTEN … established (#2)`) and its client received ids 1 to 5. The doorbells rung during the outage were lost, as `NOTIFY` is not durable; the feed read the log from its head on re-subscribe, so the outage cost 250 ms of latency and no events.

### Carrier comparison

| | **PG doorbell + log (chosen)** | PG `NOTIFY` with the full event | Kafka (SmallRye Reactive Messaging) | Redis pub/sub | Pin to one replica |
|---|---|---|---|---|---|
| New reactor dependencies | none (`PgSubscriber` is in `vertx-pg-client`) | none | `quarkus-messaging-kafka`, `kafka-clients` | `quarkus-redis-client` | none |
| New infrastructure | none; one extra connection per replica | none | a Kafka cluster (three brokers for HA), topics, retention, monitoring | a Redis (with Sentinel for HA) | none |
| Payload limit | none (the event is a row; 9.5 KB delivered in the demo) | **8000 bytes**; a larger event fails *after* the write committed (demonstrated: `payload string too long`, event lost) | 1 MB default | no practical limit | n/a |
| Tied to the database commit | yes, `NOTIFY` is transactional | yes if sent in the transaction | **no**: publish after commit is a dual write; making it safe needs a transactional outbox and a relay (CDC or a poller), i.e. this log *plus* a broker | **no**, same dual write | n/a |
| Order | per workspace, commit order, gap-free | commit order | per partition, publish order | publish order | commit order within the one JVM only if delivery is synchronous |
| Reconnect and resume | lost doorbells cost latency; client resume replays from the log | lost notifications are lost; no replay | consumer offsets; a per-pod consumer group for fan-out, mapped from `Last-Event-ID` | lost; no replay (Streams would add replay) | the restart *is* the outage |
| Operational burden | a prune job; `LISTEN` needs a session-mode connection (no PgBouncer transaction pooling) | as left | a second stateful system to run, upgrade and back up | a second stateful system | Recreate rollouts with downtime |

**Why the doorbell rather than the full event in `NOTIFY`.** The full-payload variant saves one indexed read per event per watching instance, and pays for it with a hard 8000-byte limit (an item with a long description cannot be announced) and no replay. The log is needed for `Last-Event-ID` resume anyway, so the doorbell makes live delivery and resume one read path.

**Why not a broker.** A broker solves cross-process fan-out at a scale Vectis does not have, and does not solve the problem it does have: the event must commit with the change. Publishing to Kafka or Redis after commit is a dual write. [§5](#5--ordering-and-loss) shows what that does to a client: one stalled publisher, and a board shows the older title indefinitely. Making a broker safe needs exactly the log this design already has, plus a relay, plus the broker. A broker becomes right when the reopen conditions below are met, and the log is the outbox it would read from, so the migration path stays open.

**Pin to one replica, costed.** The staging patch would change `replicas: 2` to `replicas: 1` and add `strategy: {type: Recreate}` to the Deployment spec. Recreate is required, not optional: with the default `RollingUpdate` (here `maxSurge` 1, `maxUnavailable` 0) the old and the new pod serve together during every rollout, and the in-JVM broadcast splits clients exactly as with two replicas. With Recreate, every deploy is an outage of pod termination plus image start plus readiness (the readiness probe runs every 10 s), every client stream drops at once, and a crash is an outage; the patch's own comment says the second replica exists "so rolling updates stay available". Pinning also leaves the replay problem unsolved: a client that reconnects after the restart still needs the log. It is rejected; the doorbell costs less than its downtime.

**Costs accepted.**

- Writes within one workspace serialise on the workspace row for the length of the transaction (measured write round trip p50 1.75 ms at 100 writes/s). VEC-45's configuration write already takes this lock. Human edit rates are orders of magnitude below the limit; bulk paths write one event, not one per row (see [§2](#2--event-contract)).
- Commits that `NOTIFY` take a cluster-wide lock to append to the notification queue, so notifying commits serialise across the whole PostgreSQL instance. Fine at tracker write rates; it is the known scaling ceiling of this approach and the first reopen condition.
- One extra connection per replica, which must be a direct (or session-pooled) connection: `LISTEN` does not survive PgBouncer transaction pooling, and does not work on a read replica. Staging connects directly.
- An event table with an extra insert per write and a prune job.
- The notification queue (8 GB in a standard installation) fills only if a listener never drains it; listeners here never open a transaction.

## 2 · Event contract

**Decision: full entity for item events, invalidation for configuration events.**

- **Item events carry the item's full post-change state.** A patch only applies to the exact base version it was computed from, so one missed or reordered patch corrupts the client silently; a full representation is idempotent and commutes under "highest `version` wins", so duplicates, replays and reordering are all harmless. It is also the shape the REST read and the write response already return, so the client has one item reducer. An item is small: the largest realistic one is its description. `changed` names the properties that changed, for VEC-17's highlight; it is a hint, never applied.
- **Configuration events are invalidations carrying the new revision.** The effective configuration is a resolved document of several kilobytes (VEC-45), it changes rarely, a configuration change also re-projects the board's columns, and an ancestor publish fans out to every descendant workspace. The client re-reads `GET /api/v1/workspaces/{key}/configuration` (ETag = revision) when the event's `revision` is above the one it holds, and ignores it otherwise.
- **An invalidation of the whole workspace is `resync`.** It tells the client to reload the snapshot and continue the stream from the snapshot's `seq`.

Envelope, the same for every event, keys in this order:

| Field | Meaning |
|---|---|
| `type` | `item.created`, `item.updated`, `item.moved`, `item.deleted`, `configuration.changed`, `resync`. Also the SSE `event` field. A new type is additive; clients ignore types they do not know. A breaking change goes to a new API version. |
| `workspaceId` | The workspace's id, not its key: keys can be re-keyed live (R-CORE-6). |
| `seq` | Position in the workspace's stream: gap-free, increasing, commit order. Also the SSE `id`, so it is what `Last-Event-ID` carries back. |
| `at` | Transaction time of the change, UTC, microseconds. |
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

**Resync** (here a cursor ahead of the stream, `Last-Event-ID: 999`; `reason` is `retention` when the log no longer holds the gap, `bulk` after a bulk write):

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

**Retention.** Proposed: keep 24 hours of events per workspace, pruned by a periodic delete; a client away longer gets `resync`. The number only trades table size against how often a laptop waking up reloads, and can be tuned without a contract change.

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

A second full run on the final code agreed: per write, slowest client p95 10.20, 11.69 and 7.13 ms for the three runs, worst single delivery 82 ms.

**Verdict: the 500 ms target in R-CORE-4 holds**, with a margin of more than thirty times at p95 for the slowest of 200 boards. The carrier's own share is the difference between delivery and the write's own round trip, about 0.7 to 1.8 ms at the median. The lower write rate shows *higher* latency than the higher one, because between paced requests the Docker Desktop VM's threads go idle and every wake-up costs; that inflates the numbers, it does not flatter them.

**Not measured here:** the network between a browser and the ingress, the ingress itself, pods on different nodes, the native image staging actually runs, and browser rendering. None of them is plausibly hundreds of milliseconds in steady state, which is why the number can stay.

**Proposal: keep 500 ms, and say what it covers.** "p95 ≤ 500 ms from commit to receipt at every connected client, in steady state. After a dropped stream, every change is delivered, in order, within the reconnect delay (1 to 3 s) plus 500 ms." A pod restart or a network change is not steady state, and the design promises no loss there rather than a latency.

## 5 · Ordering and loss

**Convergence rule.** The client's store holds each item with its `version` and applies any representation of it (stream event, its own write's HTTP response, a snapshot row) only if that `version` is higher than the one it holds; otherwise it drops it. Configuration applies the same rule to `revision`. On the stream, a `seq` that is not the last one plus 1 cannot happen in this design (see §1), so the client may treat it as a protocol error and reconnect with its last `seq`. `resync` reloads the snapshot. This needs **`item.version`, which does not exist**: today `ItemRepository.move` and `moveToSprint` write blind (`update item set … where id = $3`). It is also what an `If-Match` on item edits (VEC-16's CRUD) would need, as `config_revision` is for configuration.

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

**Loss.** None in the chosen path: an event exists if and only if its change committed, the log holds it, and every reconnect (the client's stream or the instance's `LISTEN` session) resumes from a position in the log. Beyond retention the client is told to reload, never left with a silent gap.

## How to run

```bash
cd docs/spikes/realtime-transport
./demo.sh all       # builds, runs sections 1-8 recorded above, removes its containers
./demo.sh up pg pg  # or: keep two instances running on 127.0.0.1:5741 (A) and :5742 (B)
curl -s -X POST 127.0.0.1:5741/spike/reset
curl -sN '127.0.0.1:5742/api/v1/workspaces/VEC/events?after=0' &
curl -s -X PATCH 127.0.0.1:5741/api/v1/workspaces/VEC/items/VEC-1 -H 'Content-Type: application/json' -d '{"title":"hello from A"}'
./demo.sh down
```

Requires Docker and a Maven repository that can resolve the Quarkus 3.37.1 BOM (the root build already does). The containers are named `vec46-pg`, `vec46-a` and `vec46-b` on the network `vec46-net`.

## Prototype quality vs. what needs hardening

**Prototype quality (as shipped):** schema created at startup, not by Flyway; a cut-down `workspace` and `item`; columns are seeded ids rather than VEC-45's projected `board_column` rows; configuration writes and template publishes only bump revisions; no authentication; no retention job (the `resync`-on-retention logic is there, nothing prunes); the hub is one monitor per instance; `item.deleted` and bulk `resync` are specified, not built; no tests, because the demonstration is the test.

**Would need hardening in VEC-17:** Flyway migration for `item.version`, `workspace.event_seq` and `workspace_event` with its prune job; every write path through the one seq-bumping helper (a write that forgets it is invisible to every board, so a test should assert that each mutating repository method appends an event); backpressure for a slow client (bounded per-stream queue, then close it and let it resume); metrics for open streams, feed lag and `LISTEN` reconnects; native-image verification of `PgSubscriber` (expected to work, unverified); the ingress settings in §3.

## Consequences

**Adopted, this means:**

- Real-time delivery is a property of the write path: every state change goes through one transaction helper that bumps `seq`, writes the change with a bumped `version`, appends the event and rings the doorbell. A change made any other way does not reach any board.
- No new infrastructure and no new third-party dependency. `vectis-server` gains a dependency on `vectis-persistence` (which it needs to serve any data anyway) and one `LISTEN` connection per replica.
- The schema gains `item.version`, `workspace.event_seq` and `workspace_event`.
- Clients hold one stream per open workspace and converge by `version`/`revision`; the snapshot carries the `seq` it is current to.
- Staging keeps `replicas: 2` and its rolling updates.

**What would reopen it:** sustained notifying commits in the high hundreds per second on one PostgreSQL instance, or measured `NOTIFY` commit contention; a deployment that must put PgBouncer in transaction mode in front of every connection with no direct path for one session per replica; events that must reach consumers other than browsers (integrations, analytics), which is when a broker reading this log as its outbox pays for itself; more than one database behind one workspace.

### VEC-17's remaining scope

With the transport decided, VEC-17 is high extension and low intension. Split by extension:

- **Server emit path** (`vectis-persistence`, `vectis-server`): the migration above; the write-path helper and every mutating repository method on it (item create, move, field edit, sprint assignment, delete; configuration delta, upgrade and ancestor publish as VEC-45, VEC-10 and VEC-71 land); the snapshot read returning `seq`; `GET /api/v1/workspaces/{key}/events` with `?after`, `Last-Event-ID`, `retry` jitter, keep-alive and `resync`; the `PgSubscriber` feed; the prune job; the metrics.
- **Client consume path** (`web`): one `EventSource` per open workspace opened at the snapshot's `seq`; the reducer applying the version rule to item events, re-reading configuration on a higher `revision`, reloading on `resync`; the remote-change highlight driven by `origin` and `changed`.

Out of VEC-17, as before: stream authentication (VEC-35), estimation poker (VEC-31).

## Premises re-checked on today's `main`

- **Still true:** staging runs `replicas: 2`; no `pom.xml` in the reactor names messaging, Kafka, AMQP or Redis; `vectis-server` holds one resource, `ExtensionDiagnosticsResource`; `WorkspaceSnapshot` is the only payload shape; ADR-04 is unwritten (`docs/adr/` holds ADR-01 only, and VEC-45's draft is ADR-03).
- **No longer true:** "there is no write path yet from which an event could be emitted". `vectis-persistence` now has reactive write methods (`ItemRepository.insert`, `insertAll`, `move`, `moveToSprint`; sprint, board and workspace repositories). No REST endpoint calls them yet, and `vectis-server` does not depend on `vectis-persistence`.
- **Missing, and needed by this design:** `item.version`. Item updates today are blind writes.
- **Rejected:** VEC-17's developer note ("use Quarkus Reactive Messaging to broadcast update details") describes the in-JVM broadcast of §1, which leaves the clients of every other replica stale.
