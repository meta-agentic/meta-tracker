---
kind: spike
space: vec
id: VEC-46
title: Real-time transport — SSE fan-out across replicas and the workspace event contract
status: REFINED
project: VEC
epic: VEC-2
priority: P2
labels:
- edition-community
- spike
- tier-Core
storyPoints: 5
estimate:
  extension: -0.3
  intension: 0.8
  quadrant: complex
  basis: up-front
  dated: '2026-08-30'
  note: 'Complex, narrow -> spike. This IS the intension split out of VEC-17 (complex+broad, refused unsplit).
    Low extension: a throwaway two-instance prototype and a findings/draft-ADR doc; nothing in vectis-server/src/main
    changes. High intension: ADR-VEC-04 is unwritten, staging runs replicas:2 so an in-JVM SSE broadcast
    is provably wrong rather than merely suboptimal, no messaging or broker dependency exists anywhere
    in the reactor, and no event payload shape exists for an incremental change. The 500ms target in R-CORE-4
    is asserted, not measured. Time-boxed to 3 days.'
---

## Origin

Split out of **VEC-17** (Server-Sent Events Workspace Synchronization) during
refinement. VEC-17 placed at *complex + broad* — high extension and high
intension — where the rule is: do not commit unsplit. This spike is the
intension. VEC-17 stays at `TO DO` until it closes.

## Type

**Spike — time-boxed to 3 working days.** Deliverable is a decision and a
demonstration, not the synchronization feature.

## The question this spike must answer

**How does a state change on one server instance reach a browser connected to a
different instance, and what does the event on the wire actually say?**

Sub-questions:

1. **Fan-out.** `deploy/k8s/overlays/staging/deployment-patch.yaml` sets
   `replicas: 2`. An SSE stream is a long-lived connection pinned to one pod, so
   a broadcast held in one JVM's memory reaches only the clients that happen to
   be connected to that pod. Every other open board silently goes stale — and
   silently, because nothing errors. What carries the event between instances:
   PostgreSQL `LISTEN`/`NOTIFY` over the existing Vert.x pool, a message broker
   (Kafka/AMQP, both currently absent — **`grep -rn "messaging\|kafka\|amqp\|redis"
   --include=pom.xml .` returns nothing across the whole reactor**), or an
   explicit decision to defer horizontal scale and pin to one replica for v1?
2. **Event contract.** What is on the wire? A full entity, a patch, or an
   invalidation hint that the client re-fetches? This directly determines the
   client work in VEC-17 and interacts with the store: `src/store/types.ts`
   already comments `WorkspaceSnapshot` as *"a bulk payload as it would arrive
   from the reactive REST/SSE edge"*, but no event shape exists for an
   incremental change.
3. **Scoping and delivery.** How does a client subscribe to one workspace or
   board rather than everything, and what happens on reconnect after a dropped
   connection — is there a resume cursor (SSE `Last-Event-ID`), or does the
   client re-hydrate wholesale and accept the gap?
4. **Budget.** Is R-CORE-4's *(target)* **500 ms to every open board** reachable
   through the chosen fan-out path, measured rather than assumed?
5. **Ordering and loss.** Can two changes to the same item arrive out of order at
   a client, and if so, what makes the store converge — a version/sequence field
   on the event, or last-write-wins with a periodic reconcile?

## Why this is genuinely open

ADR-VEC-01 **R-CORE-4** decides the *requirement*: state changes propagate over
SSE, visible on every open board within 500 ms, remote updates visually
highlighted. **ADR-VEC-04 (real-time transport) is listed there as a planned
follow-on and has not been written.** The repo has nothing to build on:
`vectis-server` holds one resource (`ExtensionDiagnosticsResource.java`), no
reactive-messaging or broker dependency exists anywhere in the reactor, and there
is no write path yet from which an event could be emitted. Sub-question 1 in
particular is not a matter of preference — the staging topology makes the naive
in-JVM broadcast provably wrong, and that is a correctness question with a
deployment-shaped answer.

## Scope

In scope — throwaway prototype on a spike branch:

* A minimal Quarkus SSE endpoint plus a second instance of the same app, and a
  demonstration that a change injected at instance A reaches a client subscribed
  to instance B. If it does not, that *is* the finding, and the prototype then
  demonstrates the chosen carrier making it work.
* A comparison of at least PostgreSQL `LISTEN`/`NOTIFY` (attractive because the
  Vert.x reactive pool is already the persistence transport, so it adds no new
  infrastructure) against one broker-based option, on: added dependencies,
  operational burden, payload size limits, and delivery semantics on reconnect.
* A written event-contract proposal with concrete example payloads.
* A propagation-latency measurement across two instances for sub-question 4.

Explicitly out of scope:

* **Implementing workspace synchronization.** VEC-17 owns that and becomes merely
  complicated once this closes.
* The client-side consumer hook, the zustand integration and the "brief visual
  flash" highlight — all VEC-17.
* The estimation-poker room state — **VEC-31**, which depends on VEC-17.
* Authentication of the SSE stream — VEC-35. Note in the findings if the chosen
  design would constrain it.
* Merging anything into `vectis-server/src/main`.

## Acceptance criteria

1. A findings document exists at `docs/spikes/VEC-46-realtime-transport.md`,
   following the shape of `docs/spikes/VEC-13-plugin-loading.md`, answering all
   five sub-questions with a decision rather than a survey.
2. Sub-question 1 is answered by a **running two-instance demonstration**, not an
   argument: the document records the command to reproduce it and the observed
   result both without a carrier (expected: client on instance B never sees the
   change) and with the recommended carrier.
3. The recommended carrier is named with its cost stated explicitly — new
   dependencies added to the reactor, new infrastructure to operate, and what it
   does on reconnect. "Pin to one replica for v1" is an acceptable recommendation
   **only** if the document also states what must change in
   `deploy/k8s/overlays/staging/deployment-patch.yaml` and what that costs during
   a rolling update.
4. Sub-question 2 is answered with at least three concrete example payloads —
   item moved, item field edited, item created — in full, as bytes on the wire,
   with the decision (full entity / patch / invalidation) and its reason.
5. Sub-question 4 is a **measured** number: propagation latency p50 and p95 from
   write on instance A to receipt on a client of instance B, with the method and
   the environment recorded, and a plain statement of whether the 500 ms target in
   R-CORE-4 holds. If it does not, propose the number that should replace it.
6. Sub-question 5 is answered with a stated convergence rule and a demonstrated
   out-of-order case.
7. The document is liftable into **ADR-VEC-04** with a Status of Proposed, and
   states clearly what VEC-17's remaining scope is once adopted.
8. `mvn -B -ntp verify` on `main` is unaffected; nothing lands in
   `vectis-server/src/main`.

## Exit condition

3 working days. A negative result closes it successfully — "no carrier meets 500
ms without a broker, so v1 pins to one replica and accepts the rolling-update
cost" is a decision, and a decision is the deliverable.

## Unblocks

VEC-17 (SSE workspace synchronization), and transitively VEC-31 (collaborative
estimation poker). Feeds ADR-VEC-04.
