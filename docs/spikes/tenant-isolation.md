# Tenant isolation (spike): draft of the multi-tenant isolation & feature gating ADR (numbered when written)

| | |
|---|---|
| **Status** | Proposed |
| **Type** | Spike (Extensibility & Architecture tier), VEC-14. Written to be lifted into the multi-tenant isolation & feature gating ADR (numbered when written). |
| **Sprint** | VEC-S5 |
| **Requirement** | [ADR-VEC-01](../adr/ADR-VEC-01-product-requirements-and-features.md) **R-SEC-2**: tenant isolation fails closed (PostgreSQL row-level security; a missing tenant binding makes rows invisible). Constrained by [ADR-VEC-02](../adr/ADR-VEC-02-identity-and-auth.md) D2 (the tenant is resolved by a registry and bound once per request with `SET LOCAL`) and [ADR-VEC-03](../adr/ADR-VEC-03-workflow-and-template-model.md) decision 7 (built-in template levels are readable by every tenant; tenant levels are tenant data). |
| **Deliverable** | This document, plus a throwaway harness in [`tenant-isolation/`](./tenant-isolation/): a standalone Maven project outside the reactor, with its recorded output in [`tenant-isolation/results/run-all.txt`](./tenant-isolation/results/run-all.txt). There is no production code and no migration on `main`. |
| **Reading** | The ADR part is Context, Decision, Rules and Consequences. Findings §1 to §5 are the evidence. The appendices map the acceptance criteria, mark each claim as measured or argued, and say how to reproduce. |
| **Unblocks** | VEC-32 (federation), VEC-33, VEC-35 (sign-in), VEC-80 (RLS input) and VEC-81 (tenant binding). It constrains VEC-74 (the stream's feed and prune job), VEC-71 (template publish and propagation) and VEC-16 (request timeouts, see §1). |

## Context

R-SEC-2 makes PostgreSQL row-level security the mechanism of tenant isolation, and requires it to fail closed. ADR-VEC-02 decided where the tenant comes from: a verified token, mapped through a registry Vectis owns, bound once per request into a `TenantContext` and applied with `SET LOCAL`. What nobody had shown is that this binding holds on the runtime query path, which is the reactive Vert.x pool (`io.vertx.mutiny.sqlclient.Pool`, through `quarkus-reactive-pg-client`). The pool hands one physical connection from request to request with no reset in between. The schema on `main` still has no tenant column and no policy.

The question: **can PostgreSQL RLS, driven by a tenant setting on the reactive pool, deliver fail-closed isolation without leaking tenant context between pooled connections, and at an acceptable cost on the read path?** And, separately: **where does the Community core hand off to gated capabilities (SOC 2 audit logging, SCIM)?**

## Decision

1. **RLS is the isolation boundary, bound per transaction, and it holds on the reactive pool, provided transaction control goes through the client's API.** Every tenant-scoped statement runs inside `pool.withTransaction`. The transaction's first statement is `select set_config('app.current_tenant_id', $1, true)`, which is the parameterised form of `SET LOCAL`.
   - The probe ran 50,000 interleaved requests over the 20 connections of the product's default pool, with 200 in flight. It included failed transactions, timed-out requests, and connections closed mid-transaction. Result: **0 leaks**.
   - Two negative controls show the probe detects a leak when one exists. A session-level `SET` leaked 1,304 times in 5,000 requests. Transaction control sent as SQL text leaked 1,011 times in 5,000: the pool does not know the connection is still inside a transaction, so the next borrower inherits it (§1).
2. **Layers: RLS enforces; an application predicate is optional and never relied on.** A query may also say `where tenant_id = $n` when that helps the planner or the reader. The guarantee is the policy alone, because it is the only layer that still holds when a query forgets its predicate. Neither layer stops SQL injection: injected SQL runs inside the bound transaction and can call `set_config` itself. Parameterised queries remain the defence there.
3. **The cost is the binding's round trips, not the policy.** Board read path, 1,000 tenants, 100,000 items, measured sequentially (recorded run, §2):

   | Mode | p50 | p95 |
   |---|---|---|
   | Application filtering, one statement | 0.49 ms | 0.62 ms |
   | Application filtering inside a transaction | 1.11 ms | 1.48 ms |
   | RLS | 1.56 ms | 2.02 ms |
   | RLS, binding pipelined into the query | 1.13 ms | 1.66 ms |

   The plans are identical: the same index scan, with the tenant predicate as a filter. The per-read cost is acceptable. A board opens with one snapshot read, and any request that writes already runs in a transaction.
4. **Isolation is core, not an extension.** Policies, the binding and the app role belong to `vectis-persistence` and `vectis-server`, whichever edition ships. Of the two named capabilities, the SPI can host only the *sink* of SOC 2 audit logging (`AuditLogger`), and only once the event is written in the change's transaction. It cannot host SCIM, which needs inbound endpoints and an identity store that no extension point offers (§4).
5. **Role design is a decision for the ADR, and three options are on the table** (§3). The runtime role is fixed by the evidence: `NOSUPERUSER NOBYPASSRLS`, owning no table. How legitimate cross-tenant work (seeding built-ins, data migrations, system jobs) gets its rows is not settled here. Recommended: `FORCE` on every tenant-scoped table, migrations run as a `BYPASSRLS` role, and system functions owned by a dedicated `BYPASSRLS` definer role. Which edition carries tenancy, audit and SCIM is the founder's call (§5).

## Rules

These rules are the normative part of the decision.

**Runtime role.** The role the reactive pool connects as is `NOSUPERUSER NOBYPASSRLS` and owns no table. Demonstrated (§3): a superuser sees all 100,000 rows with no binding, and so does the table owner when `FORCE` is off.

Today the Flyway (JDBC) datasource and the runtime use one credential, and the test container's user is a superuser. A suite run that way would pass every RLS test vacuously, so tests must connect as the runtime role. The roles behind migrations and system jobs are the options of §3.

**Binding.** `select set_config('app.current_tenant_id', $1, true)` is the first statement of every tenant-scoped transaction. It is parameterised, unlike `SET LOCAL`, which takes no bind parameter and would invite string concatenation. Never use `is_local = false`, a plain `SET`, or a binding outside a transaction:

- Outside a transaction, `set_config(…, true)` lasts one statement, so the binding is lost. That fails closed, but the request then sees nothing.
- A session `SET` stays on the pooled connection for the next borrower. That is the leak the first control demonstrates.

The binding may be pipelined with the transaction's next statement. The Vert.x client sends commands on one connection in order, so this saves a round trip. Were they ever executed out of order, the query would run unbound and return no rows: wrong, but closed.

**Transaction control only through the client's API.** Transactions are opened by `pool.withTransaction`, or `SqlConnection.begin()` on a borrowed connection. Never by SQL text: no `BEGIN`, `COMMIT`, `ROLLBACK`, `SET` or `RESET` statements, in a query string or a multi-statement simple query.

A connection that is closed inside a transaction it opened as text goes back to the pool still inside that transaction, with its binding. The next borrower runs in that same transaction and sees the other tenant's rows, even through `withTransaction`, whose own `BEGIN` only draws a warning. Demonstrated by the second control (§1). A connection closed inside an API transaction is rolled back, and nothing leaks (the `ABANDONED` kind, §1).

Guard, recommended and not built: a test over the SQL string constants in the persistence code. It fails on any statement that starts with one of those keywords, on any string that holds more than one statement (a `;` outside a literal, which is how `begin; …` hides a transaction inside an ordinary-looking query), and on any `set_config(…, false)`. A keyword check alone would miss the last two.

**Policy expression.** `tenant_id = nullif(current_setting('app.current_tenant_id', true), '')::uuid`. The `nullif` is required. Once a session has set the variable, it reads back as `''` (not null) after the transaction ends, and `''::uuid` raises an error (demonstrated, §3). Without `nullif`, an unbound read on a used connection errors instead of returning nothing. That still fails closed, but as an error, not as zero rows.

**Where `tenant_id` lives.** It is a column on every tenant-scoped table (`workspace`, `board`, `board_column`, `item`, `sprint`, `workspace_event`, template levels, and every later table), so that each policy is a column comparison and not a join. Its consistency with the parent row is kept by composite foreign keys, for example `(workspace_id, tenant_id) references workspace (id, tenant_id)`. Argued, not prototyped.

The tenant registry is itself under RLS: a bound session sees its own tenant row only. That leaves an open design point for the ADR: ADR-VEC-02 D2 maps the verified `(iss, tenant claim)` to a tenant *before* anything is bound, and an unbound session sees no tenant at all. The lookup therefore needs either a definer function that resolves exactly one claim to one tenant (under the preconditions below), or a claim-to-tenant mapping table outside the tenant policy that holds no tenant data.

**References across rows.** Referential checks do not apply RLS. A row may therefore reference a parent its tenant cannot see, unless the policy's `WITH CHECK` requires the parent to be visible, on **INSERT and UPDATE** alike. For example: `parent_id is null or exists (select 1 from template_level p where p.id = template_level.parent_id)`.

Demonstrated (§1): with a policy on `tenant_id` alone, tenant A extended tenant B's template level. With the check on INSERT only, tenant A could still re-parent its own level onto B's by UPDATE. The outer column must be qualified with the table name: unqualified, `parent_id` binds to the subquery's own row and the check refuses every write.

**Global rows.** Built-in template levels have `tenant_id` null. Their read policy is `tenant_id is null or tenant_id = <bound>`, and their write policies require `tenant_id = <bound>`. So built-ins are readable by every tenant, even unbound, and writable by none (demonstrated, §1). They are seeded by a release, through whichever migration role §3 settles on: under `FORCE`, the plain owner cannot.

**Cross-tenant system work.** Some paths serve many tenants by design: the real-time feed and its catch-up poll (VEC-74), the event prune job, and template propagation (VEC-71). These either bind each tenant in turn (the workspace row names its tenant), or call a narrow `SECURITY DEFINER` function that takes the workspace id. Such a function must be owned by a role that bypasses the policy (option 3) or that a policy admits (option 2). Owned by a forced owner, it sees nothing (demonstrated, §3). These paths never share a `BYPASSRLS` pool with request paths.

**Preconditions of every definer function** (option 3, and any registry lookup). PostgreSQL grants `EXECUTE` on a new function to `PUBLIC` by default, so a definer-role function is callable by every role in the cluster: in review, an unrelated role called one and saw all 100,000 rows. Each such function therefore:

- runs `REVOKE EXECUTE … FROM PUBLIC`, then grants `EXECUTE` to the runtime role only;
- pins `SET search_path` to its own schema, so a caller cannot shadow the tables or operators it uses;
- takes arguments that scope it, for example one workspace id, and never returns more than that scope.

**Write path.** VEC-73's write-path helper already opens the transaction and locks the workspace row first. The binding becomes that transaction's first statement, pipelined with the lock, so writes pay no extra round trip. Argued.

## Consequences

**Adopted, this means:**

- The persistence layer gains one entry point for tenant-scoped work: a transaction that binds the request's `TenantContext` first. A tenant-scoped query issued through the bare pool returns nothing, which fails closed and shows up in the first test that exercises it.
- Two or three database roles per deployment instead of one: the runtime role, a migration role, and (with option 3) a definer role. Tests run as the runtime role.
- Every tenant-scoped table gains `tenant_id`, a policy, `FORCE`, and a composite foreign key to its parent. That includes the tables VEC-73 adds.
- Template levels need no re-refinement. One nullable `tenant_id` column and four policies cover built-in and tenant levels, as ADR-VEC-03 decision 7 requires (§1). The change is additive.
- `ExtensionRegistry` stays the seam for gated *implementations* of a contract the core defines. Isolation itself is not behind it.
- A request timeout does not stop the request's transaction. The client gives up waiting; the transaction runs on and commits (§1). VEC-16 must treat a timed-out write as possibly applied.

**Costs accepted.**

- About one database round trip per tenant-scoped request, for the transaction and the binding: about 0.5 to 1 ms in the measured environment (§2). A request that already runs in a transaction pays only the pipelined binding.
- Every read runs in a transaction, `BEGIN` and `COMMIT` included. A read issued as a bare pool statement is not merely slower: it returns nothing.
- Cross-tenant system jobs bind per tenant, or go through reviewed `SECURITY DEFINER` functions.

**What would reopen it:**

- A leak observed by the probe under a different pool configuration, a Quarkus or Vert.x upgrade, or PgBouncer in transaction mode. The probe is the regression test.
- A read path where the binding round trip measurably dominates: very chatty requests that cannot share a transaction.
- A requirement for database-level isolation stronger than rows, such as a schema or database per tenant, for example for data residency.
- A managed PostgreSQL on which the chosen role option cannot be created.

## Findings

The sections below are the evidence the decision rests on. They are not part of the ADR text.

## 1 · Correctness: can request B see request A's tenant?

**Setup.** PostgreSQL 16.14 (`postgres:16-alpine`, default settings). The pool is Quarkus 3.37.1's reactive PostgreSQL client with the product's defaults (`max-size` unset, so 20 connections), connected as `spike_app` (`NOSUPERUSER NOBYPASSRLS`, owner of nothing). The schema owner is a separate role, `spike_owner`. `item` copies the product's columns plus `tenant_id`; RLS is enabled and forced, with the policy under [Rules](#rules). There are 1,000 tenants with 100 items each.

**Probe** ([`LeakProbe.java`](./tenant-isolation/src/main/java/io/vectis/spike/tenancy/LeakProbe.java)). Requests are interleaved at random across seven kinds, for random tenants, with 200 in flight against 20 connections. Each connection therefore passes from kind to kind and tenant to tenant about 2,000 times. Every read reports the backend pid that served it, the tenant setting it saw, the rows it could see, and how many of those belong to another tenant. A leak is any bound read that sees another tenant's row, sees fewer than its own 100 rows, or sees a setting other than its tenant. For an unbound read, a leak is any row at all, or any non-empty setting.

| Kind | What it does | Expected |
|---|---|---|
| `BOUND` | `withTransaction`: `set_config(t, true)`, then read | exactly tenant t's 100 rows |
| `UNBOUND` | a bare pool statement, no binding | 0 rows |
| `UNBOUND_TX` | `withTransaction`, no binding | 0 rows |
| `FAILING` | binds, then fails (`1/0`) inside the transaction | rolled back |
| `TIMED_OUT` | binds, sleeps 20 ms, writes a marker row; its subscriber gives up after 5 ms (a request timeout) | the next borrower sees nothing of it |
| `ABANDONED` | borrows a connection, `begin()` through the API, binds, and closes the connection mid-transaction | rolled back; the next borrower sees nothing of it |
| `BOUND_OUTSIDE_TX` | `set_config(t, true)` as its own pool statement, then a read as another | 0 rows: the binding is lost, not leaked |

Results, as recorded in [`results/run-all.txt`](./tenant-isolation/results/run-all.txt):

```
probe: mode=LOCAL requests=50000 in-flight=200 seed=14 elapsed=8748 ms
probe: kinds {BOUND=27428, UNBOUND=7440, UNBOUND_TX=3456, FAILING=3001, TIMED_OUT=3032, ABANDONED=3085, BOUND_OUTSIDE_TX=2558}
probe: 20 physical connections served the reads; reads per connection min 1833 max 2211
probe: 9013 reads came straight after a BOUND read on the same connection (completion order)
probe: expected failure x3001  FAILING: PgException ERROR: division by zero (22012)
probe: expected failure x3032  TIMED_OUT: TimeoutException
probe: TIMED_OUT requests 3032, of which committed their write anyway: 3032
probe: LEAKS 0 {}
```

**A timeout is not an abandonment.** Mutiny's timeout cancels the subscriber, not the transaction. All 3,032 timed-out transactions ran to the end and committed their marker row; the connection returned to the pool only then. The first version of this probe called this kind "cancelled, mid-transaction", which it was not. The `ABANDONED` kind now closes a connection inside an API transaction, and the client rolls it back before the connection is reused. The consequence for request handling (VEC-16) is under Consequences.

**First negative control:** the binding made session-level (`set_config(t, false)`, a plain `SET`):

```
probe: mode=SESSION requests=5000 in-flight=200 seed=14 elapsed=1011 ms
probe: LEAKS 1304 {UNBOUND=742, UNBOUND_TX=306, BOUND_OUTSIDE_TX=256}
probe: leak example Outcome[kind=UNBOUND, tenant=095d3e70-…, read=true, pid=733, setting=fad13e28-…, visible=100, foreign=100, error=null]
```

**Second negative control:** transaction control as SQL text. `BOUND` becomes `begin; select set_config(t, true)` as one bare pool statement. `ABANDONED` becomes `query("begin")` on a borrowed connection, then the binding and `close()`:

```
probe: mode=TEXTUAL requests=5000 in-flight=200 seed=14 elapsed=728 ms
probe: LEAKS 1011 {UNBOUND=576, UNBOUND_TX=241, BOUND_OUTSIDE_TX=194}
probe: leak example Outcome[kind=UNBOUND, tenant=095d3e70-…, read=true, pid=761, setting=c7ab7ba9-…, visible=100, foreign=100, error=null]
```

The `UNBOUND_TX` leaks are the point: a request that did everything right, inside `withTransaction`, still saw another tenant's rows, because it ran inside a transaction an earlier borrower had left open.

The controls show three things:

- The probe detects a leak when there is one.
- The binding's scope, not the pool, decides isolation. Vert.x hands connections back without any reset (no `DISCARD ALL`), so whatever a session sets stays on the connection.
- The client's transaction API is load-bearing: the pool rolls back only the transactions it knows about.

**Verdict on sub-question 1: no leak, under two rules.** The binding must be transaction-local, and transactions must be opened through the client's API. Under those rules, the binding scoped to its transaction in every case tried: commits, rollbacks, timeouts and connections closed mid-transaction. The client's per-connection prepared-statement cache did not carry a binding either: the probe's read is one prepared statement, executed about 2,000 times per connection across tenants. Every request of the run counted: none was dropped by the harness, and the failures are exactly the `FAILING` and `TIMED_OUT` kinds.

**Template levels** (ADR-VEC-03 decision 7; `./run.sh templates`):

```
templates: PASS  tenant A sees the built-in and its own level, not B's
templates: PASS  unbound sees the built-in only
templates: PASS  tenant A cannot write a built-in level (tenant_id null)
templates: PASS  tenant A cannot update the built-in
templates: PASS  tenant A cannot write a level owned by B
templates: PASS  tenant A cannot extend B's level (policy checks the parent is visible)
templates: PASS  tenant A cannot re-parent its own level onto B's (the same check on UPDATE)
templates: SHOWN  with a policy on tenant_id alone, tenant A CAN extend B's level (a foreign key check does not apply RLS)
```

This is additive to the VEC-45 and VEC-66 constraints: one nullable column and four policies, no change to the template model. Authority *within* a tenant (who in the tenant may publish which level, VEC-71) sits above row visibility and is not decided here.

## 2 · Cost: the board read path

**Query:** `ItemRepository.findByColumn`, which reads every item of one column of one board in rank order. Its columns and its index `(board_id, column_id, rank)` are copied from `main`. Each request picks a random tenant and one of its three columns, about 33 rows, and must get rows back.

**Dataset:** 1,000 tenants, 100 items each (100,000 rows), in `item` (RLS) and in an identical `item_plain` (no RLS, same indexes, plus an index on `tenant_id` in both).

**Modes:**

- `APP`: `item_plain … where tenant_id = $1 and board_id = $2 and column_id = $3`, as one pool statement.
- `APP_TX`: the same, inside `withTransaction`.
- `RLS`: `withTransaction`, then `set_config`, then the product's query unchanged on `item`.
- `RLS_PIPELINED`: as `RLS`, with `set_config` and the query sent together.
- `RLS_AND_APP`: the policy, plus the predicate in the query.

**Run shape:** 1,000 warm-up requests per mode are discarded, then 5 rounds of 2,000 requests per mode, with the mode order shuffled each round. This runs once sequentially and once with 16 requests in flight (below the pool's 20).

**Environment:** Apple M3 Pro (11 cores, 18 GB); macOS; Docker Desktop 29.8.2 (VM with 11 vCPUs, 8 GB); PostgreSQL 16.14 (`postgres:16-alpine`, default settings) in a container; the harness on the host (OpenJDK 25.0.3, Quarkus 3.37.1, JVM mode), connecting through Docker's published port. A round trip in this setup is a few tenths of a millisecond, which is what the modes differ by.

**Plans** (as the app role, from the same run):

```
plan APP: Index Scan using item_plain_board_column_rank_idx on item_plain (actual rows=33 loops=1)
plan APP:   Index Cond: ((board_id = '…'::uuid) AND (column_id = '…'::uuid))
plan APP:   Filter: (tenant_id = '…'::uuid)
plan RLS: Index Scan using item_board_column_rank_idx on item (actual rows=33 loops=1)
plan RLS:   Index Cond: ((board_id = '…'::uuid) AND (column_id = '…'::uuid))
plan RLS:   Filter: (tenant_id = (NULLIF(current_setting('app.current_tenant_id'::text, true), ''::text))::uuid)
```

**Results** (latency per request in ms, from the recorded run):

| Mode | Sequential p50 | p95 | p99 | 16 in flight p50 | p95 | p99 |
|---|---|---|---|---|---|---|
| `APP` (one statement) | 0.491 | 0.616 | 0.739 | 0.892 | 1.556 | 2.088 |
| `APP_TX` (in a transaction) | 1.107 | 1.476 | 1.618 | 2.084 | 3.025 | 4.335 |
| `RLS` | 1.557 | 2.022 | 2.447 | 2.715 | 3.887 | 4.892 |
| `RLS_PIPELINED` | 1.132 | 1.658 | 1.774 | 2.292 | 3.216 | 4.185 |
| `RLS_AND_APP` | 1.537 | 1.968 | 2.248 | 2.709 | 3.730 | 4.918 |

10,000 requests per cell. The absolute numbers moved by up to 0.25 ms at p50 between runs on this machine, but the order of the modes did not. Tail latency is noisier: in one earlier run at 16 in flight, `RLS_PIPELINED`'s p99 was above `APP_TX`'s (6.10 against 4.98 ms); in this one it is below (4.19 against 4.34 ms).

**Verdict on sub-question 2: acceptable.**

- The policy itself costs nothing measurable. The plan is the same index scan with the same filter, and `RLS_PIPELINED` tracks `APP_TX`.
- What costs is the request shape. A bare statement is one round trip. `RLS` is `BEGIN`, `set_config`, the query and `COMMIT`, which is four. Pipelining the binding brings it to three.
- Against the bare statement, RLS costs about +1.1 ms at p50 sequentially (3.2×), and +0.6 ms when pipelined. At 16 in flight, it costs about +1.4 to +1.8 ms at p50.
- These are millisecond differences on a read that opens a board, against R-CORE-4's 500 ms budget for a change to reach every board.
- Adding the application predicate on top of RLS (`RLS_AND_APP`) neither helps nor hurts.

A ratio alone would mislead here: 3.2× sounds decisive, and 1 ms is not.

## 3 · Roles: who bypasses, and how cross-tenant work gets its rows

**Who bypasses the policy** (`./run.sh bypass`):

```
superuser vectis, unbound, sees 100000
owner, FORCE on, unbound, sees 0
owner, FORCE off, unbound, sees 100000
app, unbound, sees 0
app, never set, setting reads NULL
app, after a committed local binding, setting reads []
ERROR:  invalid input syntax for type uuid: ""
```

The last two lines are why the policy needs `nullif`. The first three are why the runtime role must be neither superuser nor owner. The product's test database user today is the container's superuser.

**What `FORCE` does to legitimate cross-tenant work** (`./run.sh roles`; each block runs in a transaction that is rolled back):

```
-- owner, FORCE on: seed a built-in, migrate data, run a SECURITY DEFINER function it owns
owner: data migration touches 0 rows
owner: definer function owned by the owner sees 0
ERROR:  new row violates row-level security policy for table "template_level"
-- option 1: migrations run as a BYPASSRLS role
migrator: data migration touches 100000 rows
migrator: built-in seeded, built-ins now 2
-- option 2: FORCE kept, plus policies scoped to the owner role
owner + owner policy: data migration touches 100000 rows
owner + owner policy: definer function sees 100000
owner + owner policy: built-in seeded, built-ins now 2
-- option 3: system functions owned by a dedicated BYPASSRLS definer role; the owner stays forced
app calling a definer-role function sees 100000
app directly, unbound, sees 0
```

With `FORCE` on and nothing else, the owner cannot seed a built-in. A data migration run as the owner silently updates nothing, and a `SECURITY DEFINER` function owned by the owner sees nothing. `FORCE` is still worth having: any connection that happens to use the owner role fails closed instead of reading every tenant. Cross-tenant work then needs one of these:

| Option | What it is | For | Against |
|---|---|---|---|
| **1. Migration role with `BYPASSRLS`** | Flyway's JDBC datasource connects as a role with the `BYPASSRLS` attribute, which owns the schema or is granted the needed rights | One attribute and no extra policies. Migrations and built-in seeding see every row, as they must. Its credential lives only where migrations run. | Creating a `BYPASSRLS` role needs a superuser (or a managed provider's equivalent; unverified per provider). A leaked migration credential reads everything. |
| **2. `FORCE` plus owner-scoped policies** | `create policy owner_all on <table> to <owner> using (true) with check (true)` on every tenant-scoped table | Everything is in the schema and visible in the policy listing; no role attribute. Owner-owned definer functions work. | One more policy per table that must never be forgotten. The owner is then as unrestricted as option 1, by a different route. |
| **3. Dedicated definer role with `BYPASSRLS`** | System functions (feed reads, prune, propagation) are `SECURITY DEFINER` and owned by a `NOLOGIN BYPASSRLS` role granted only what they read; the runtime role may only execute them | The narrowest grant: each cross-tenant path is one reviewed function. No login can use the role directly. | Covers system jobs only; migrations still need option 1 or 2. Each function is a reviewed hole in the policy, so its arguments must scope it, for example to one workspace. `EXECUTE` is granted to `PUBLIC` by default and must be revoked, and `search_path` pinned (see Rules). |

**Recommendation, for the ADR to decide:** options 1 and 3 together. `FORCE` stays on every tenant-scoped table. Migrations and built-in seeding run as a `BYPASSRLS` migration role on the JDBC datasource only. Cross-tenant system paths are `SECURITY DEFINER` functions owned by a `NOLOGIN BYPASSRLS` definer role. The runtime role remains `NOSUPERUSER NOBYPASSRLS` and owns nothing. Option 2 is the fallback where a `BYPASSRLS` role cannot be created.

## 4 · Gating seam

The seam that exists is `vectis-extension-spi` plus `ExtensionRegistry`. Each extension point has an always-present `@Priority(0)` community floor, and a gated implementation outranks it with `@Priority(100)` and `@LookupIfProperty`, as `TamperEvidentAuditLogger` does ([plugin-loading findings](./plugin-loading.md)).

```mermaid
flowchart LR
  subgraph Core["Core (vectis-persistence, vectis-server): no extension point"]
    TC["TenantContext (ADR-VEC-02 D2)"] --> TX["tenant transaction: set_config first"]
    TX --> RLS["RLS policies, FORCE, app role"]
    TX --> WP["write path (VEC-73): change + event + audit row, one transaction"]
  end
  subgraph SPI["vectis-extension-spi via ExtensionRegistry"]
    AL["AuditLogger: sink / shipper"]
  end
  WP -- "audit outbox rows, read after commit" --> AL
  AL --> CF["community floor: local log"]
  AL --> EF["gated: tamper-evident, external shipping"]
  SCIM["SCIM 2.0 endpoints + directory store"] -. "no extension point can host it" .-> SPI
```

| Capability | Extension point | Fits? | What it would take |
|---|---|---|---|
| Tenant isolation (binding, policies, roles) | none | **must not be an extension** | Fail-closed cannot depend on whether a plugin JAR is present. The policies are schema, migrated by the core, and the binding sits on the core's query path. Whatever the edition decision, the code is core. At most, a *mode* (single-tenant or multi-tenant) is configuration. |
| SOC 2 audit logging: the sink (tamper evidence, external shipping, retention) | `AuditLogger` (exists) | **yes, as a sink** | `AuditEvent` already carries `tenantId`, `actor` and `action`. A gated implementation outranks the floor with no core change. |
| SOC 2 audit logging: completeness | none today | **not as the SPI stands** | `record` is `void`, "must not throw", and runs outside the change's transaction. A crash between commit and `record` loses the event, while an auditor (SOC 2's monitoring criteria) expects every change to leave a record. The fix is core-side: an audit row written in the change's transaction (VEC-73's write path is the place, beside `workspace_event`), and `AuditLogger` as the shipper that reads the outbox after commit. The SPI would also need a read side, or the core a query API, for an auditor to retrieve and export records, and the audit table needs its own tenant-scoped read policy. Today nothing calls `record` at all. |
| SOC 2 audit logging: authentication and permission events | none | **core, not SPI** | Sign-in and permission changes originate in `vectis-server` (VEC-35, VEC-81) and must emit `AuditEvent`s there. |
| SCIM 2.0 provisioning (RFC 7643/7644) | none | **no** | SCIM is an *inbound* HTTP protocol (`/scim/v2/Users`, `/Groups`, its own per-tenant bearer credential) that writes users and group memberships. Every extension point in the SPI is something the core calls (a sink, a sync connector, a backlog source), resolved by priority or selected by id. It has no way to contribute HTTP endpoints, no identity or membership store contract, and the user and membership schema does not exist yet (VEC-81's registry is the start). Quarkus discovers JAX-RS resources at build time, so a SCIM JAR on the classpath *is* the endpoint: `@LookupIfProperty` gates beans, not resources. Gating one at runtime needs a request filter or a build-time `@IfBuildProperty`. |
| The gate itself | `@LookupIfProperty` + `@Priority` | **as a deploy switch** | It is a property, not a licence check. If the editions must verify a signed licence, that is a core `LicenseVerifier` behind the same lookup, which is a founder's decision. |

**Verdict on sub-question 3.** The SPI hosts the audit *sink* today. It hosts audit *completeness* only after a core change: an in-transaction audit outbox, with `AuditLogger` as the shipper. It cannot host SCIM. That needs either SCIM endpoints and a directory store in the core, gated by configuration, or a new extension point (a core-owned `/scim/v2` endpoint delegating to a `ProvisioningHandler` SPI over a core `DirectoryStore`).

## 5 · Editions: options for the founder, not decided here

The sources disagree:

- The [README](../../README.md#editions) puts "multi-tenant security" in the Community edition, and Enterprise is operational tooling (operator, HA, upgrades, backup and key rotation).
- ADR-VEC-01 §6 says Community carries every requirement, R-SEC-2 included.
- This item's title says "Enterprise Edition Multi-Tenant Isolation", while its label is `edition-community`.

| Option | Community carries | Gated (Enterprise or licensed) | Evidence for | Evidence against |
|---|---|---|---|---|
| **A** | tenancy, RLS, audit outbox and floor sink, SCIM | operational tooling only (as the README says) | matches the README and ADR-VEC-01; isolation is core code either way (§4) | no security feature to sell |
| **B** | tenancy, RLS, audit outbox and floor sink | tamper-evident or externally shipped audit sink; SCIM | uses the seam that exists for the sink (§4); SCIM is commonly an enterprise feature | SCIM needs a new extension point or a gated core endpoint (§4) |
| **C** | single-tenant only | multi-tenant mode | the line most buyers recognise: hosting many organisations on one deployment is the commercial use case, and a single-tenant Community deployment needs no tenant registry or provisioning | the policies, binding and roles are core code in both editions, so this gates configuration, not code; it contradicts the README and ADR-VEC-01 §6 |

## Appendix A · Acceptance criteria: how each is met

| AC | Where it is met |
|---|---|
| **1. Findings document answering the three sub-questions** | This document. The path follows the repository's current naming (`realtime-transport.md`, `template-instance-model.md`), not the `VEC-14-…` path the item predates the convention with. Sub-questions 1 to 3: §1, §2, §4. |
| **2. Sub-question 1 by a reproducible test, with pass/fail and pool configuration** | §1: `./run.sh probe` (exit 0 when there is no leak), `./run.sh control` and `./run.sh control-textual` (exit 0 when the control does leak), with the pool configuration and the recorded output. |
| **3. p50/p95 for RLS and application filtering at 1,000 tenants, with dataset, hardware and Postgres version** | §2: absolute numbers, sequential and at 16 in flight, with dataset, environment and plans. |
| **4. A recommendation liftable into the ADR** | [Decision](#decision), [Rules](#rules) and [Consequences](#consequences): RLS enforcing, the application predicate optional, binding per transaction through the client's API, and the role options with a recommendation (§3). |
| **5. Each Enterprise capability mapped to an extension point, or why the SPI cannot host it** | §4, table and diagram. |
| **6. Throwaway code under `docs/spikes/`, nothing in `vectis-persistence/src/main`** | [`tenant-isolation/`](./tenant-isolation/), a standalone Maven project that is not a reactor module. The `docs/spikes/vec-14/` path in the item predates the repository's topic naming. |
| **7. `mvn -B -ntp verify` on `main` unaffected** | No reactor module changed. The root build was verified on this branch. |
| Additive: built-in `workspace_template` rows readable by every tenant (VEC-45); tenant levels scoped (VEC-66, VEC-71) | §1, template levels: additive, no re-refinement needed. |

## Appendix B · Evidence: measured or argued

| Claim | Basis |
|---|---|
| No leak under a transaction-local binding through the client's transaction API, including rollbacks, timeouts and connections closed mid-transaction | **Measured**: 50,000 requests, 0 leaks (§1); one configuration (20 connections, 200 in flight, JVM mode) |
| The probe detects a leak | **Measured**: session binding, 1,304 leaks in 5,000; textual transaction control, 1,011 in 5,000 (§1) |
| A timed-out request's transaction still commits | **Measured**: 3,032 of 3,032 (§1) |
| Superuser always bypasses; owner bypasses without `FORCE` | **Demonstrated** (§3) |
| `''` after a committed local binding; `nullif` needed | **Demonstrated** (§3) |
| Under `FORCE`, the owner cannot seed, migrate or run definer functions over other tenants; options 1 to 3 restore each | **Demonstrated** (§3), each in a rolled-back transaction |
| Template-level policies hold on INSERT and UPDATE; a foreign key check bypasses RLS | **Demonstrated** (§1) |
| The policy adds no measurable cost; the round trips do | **Measured** (§2) in one environment; managed PostgreSQL and the native image are **not** measured |
| Pipelining the binding is safe | **Measured** indirectly: 10,000 pipelined reads under RLS all returned rows, which they could not have done without the binding in force |
| Composite foreign keys keep `tenant_id` consistent | **Argued**; not prototyped |
| Binding pipelined with VEC-73's workspace lock costs writes nothing extra | **Argued** from the `RLS_PIPELINED` measurement |
| A build-time guard against textual transaction control | **Recommended**, not built |
| A definer function is callable by any role unless `EXECUTE` is revoked from `PUBLIC` | **Demonstrated** in review (an unrelated role saw all 100,000 rows); not in the recorded run |
| The D2 registry lookup needs a path outside the tenant policy | **Argued**; open design point for the ADR |
| `BYPASSRLS` roles are available on the target managed PostgreSQL | **Unverified** |
| Audit completeness needs an in-transaction outbox; SCIM cannot be hosted by the SPI | **Argued** from the SPI's contracts and Quarkus's build-time resource discovery |
| No leak behind PgBouncer | **Not tested**. Transaction pooling preserves `set_config(…, true)` by construction, but the probe should be rerun behind it before such a deployment |

## Appendix C · How to run

```bash
cd docs/spikes/tenant-isolation
./run.sh all     # own container vec14-pg on 127.0.0.1:5714, build, then every section above
./run.sh down    # drop the database and roles, and the container if run.sh made it

# against an existing PostgreSQL 16 container instead of starting one:
PG_CONTAINER=<name> PG_PORT=<published port> PG_SUPERUSER=<superuser> ./run.sh all
```

It requires Docker and a Maven repository that can resolve the Quarkus 3.37.1 BOM (the root build already does). Knobs, passed as `-D` system properties to the jar in `run.sh`'s `run()`: `spike.tenants`, `spike.items-per-tenant`, `spike.probe.requests`, `spike.probe.in-flight`, `spike.bench.per-round`, `spike.bench.rounds`, `spike.seed`. The harness's own bookkeeping tables (`bench_target`, `probe_commit`) are readable by the app role; they are not tenant data.
