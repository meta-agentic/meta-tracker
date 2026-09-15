---
kind: story
space: vec
id: VEC-17
title: Server-Sent Events (SSE) Workspace Synchronization
status: TO DO
project: VEC
epic: VEC-2
storyPoints: 8
priority: P2
labels:
- Sprint-2
- VECTIS-007
- edition-community
- tier-Core
estimate:
  extension: 0.5
  intension: 0.7
  quadrant: complex + broad
  basis: up-front
  dated: '2026-08-30'
  note: 'Complex + broad -> REFUSED, not refined. Intension split out as VEC-46 (real-time transport spike):
    ADR-VEC-04 unwritten, staging runs replicas:2 so an in-JVM SSE broadcast is provably wrong, no messaging/broker
    dependency exists in the reactor, and no incremental event payload shape is defined. Extension spans
    vectis-server, vectis-persistence and web. Additionally blocked: there is no write path to emit events
    from (vectis-server has one resource, ExtensionDiagnosticsResource). Stays TO DO; re-point when VEC-46
    closes.'
---

## Description

As a Distributed Team Member, I want my board view to update automatically when a colleague moves a card or updates a description, So that our daily sync board remains accurate across all screens without manual page refreshes.

**Developer Notes**
Use Quarkus Reactive Messaging to broadcast update details. Open an SSE endpoint (@Produces(MediaType.SERVER_SENT_EVENTS)) that streams JSON event payloads straight to a consumer hook within the frontend Zustand store.

---

*Backlog ref: VECTIS-007 · Type: Story · Tier: Core · Planned: Sprint 2*

## Acceptance criteria

- Modifying an issue status on one screen must propagate that visual change to all other active browsers looking at the same board within 500 milliseconds.
- Incoming remote updates must be clearly highlighted with a brief visual flash to prevent user confusion.

## Refinement outcome — NOT REFINED, split (2026-08-30)

Placed at **extension +0.5 / intension +0.7 — complex + broad**. The prescription
for that quadrant is binding: **do not commit unsplit.**

**What is undefined (the intension).** R-CORE-4 in ADR-VEC-01 decides the
requirement — SSE, 500 ms to every open board, remote updates highlighted — but
**ADR-VEC-04 (real-time transport) is listed there as a planned follow-on and has
not been written.** Three things are genuinely open, and one of them is a
correctness bug waiting to be built:

* **Fan-out across replicas.** `deploy/k8s/overlays/staging/deployment-patch.yaml`
  sets `replicas: 2`. An SSE stream is pinned to one pod, so the obvious
  implementation — the one the developer note describes, an in-JVM reactive
  broadcast — reaches only the clients connected to the pod that handled the
  write. Every other open board goes stale *silently*. There is no
  reactive-messaging, broker or notify dependency anywhere in the reactor
  (`grep -rn "messaging\|kafka\|amqp\|redis" --include=pom.xml .` returns
  nothing), so the carrier is not merely unimplemented, it is unchosen.
* **The event contract.** Full entity, patch, or invalidation hint? Nothing in the
  repo defines an incremental change payload.
* **Ordering, reconnect and resume.** No convergence rule exists.

→ split out as **VEC-46**, a 3-day spike, now REFINED.

**What is broad (the extension).** A server SSE endpoint, an event producer wired
into a write path, the carrier between instances, a client consumer hook, the
zustand integration, and the highlight treatment — across `vectis-server`,
`vectis-persistence` and `@vectis/web`.

**Also blocked on:** there is no write path to emit events from. `vectis-server`
contains exactly one resource, `ExtensionDiagnosticsResource.java`; item mutation
arrives with VEC-10/VEC-16. An SSE story cannot be demonstrated before something
can change.

**When VEC-46 closes,** re-point. With the transport decided this should be
*complicated* — high extension, low intension — and the prescription becomes
split by extension (server emit path / client consume path) rather than estimate
up.
