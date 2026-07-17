# VEC-9 — Time-Ordered Primary Key Performance Matrix (spike)

| | |
|---|---|
| **Type** | Spike (Core tier) |
| **Sprint** | VEC-S1 |
| **Requirement** | Resolves the identity spike behind [ADR-VEC-01](../../adr/ADR-VEC-01-product-requirements-and-features.md) **R-CORE-2** |
| **Deliverable** | Benchmark + recommendation. **Not** a key migration (see [Follow-up](#follow-up-migration-story)). |

## Question

R-CORE-2 requires item identity that is **application-generated, lock-free, and
time-ordered (UUIDv7/ULID family)**, with a target of *"sustain 100,000
concurrent write requests in the identity spike without index fragmentation."*

Two candidates fit the "time-ordered" family: **UUIDv7** (RFC 9562) and
**ULID**. This spike measures both, application-side, to pick one and to prove
the fragmentation target is met — so board writes never wait on a relational
table queue during large distributed planning sessions.

## Why time-ordered at all

A board's primary key is inserted on the hot path of every item create and, as
a foreign key, sits in many secondary indexes. With a **random** key (UUIDv4)
each insert lands at a random position in the B-tree: pages split, the working
set of dirty pages explodes, and the index fragments. A **time-ordered** key
inserts at the right-hand edge — every insert appends, pages fill sequentially,
no fragmentation. That is the whole point of R-CORE-2, and the `inv%` column
below measures it directly.

## Method

Self-contained JDK benchmark — [`PkBench.java`](./PkBench.java), zero
dependencies so it runs before the Maven build lands:

```bash
java docs/spikes/vec-9/PkBench.java
```

It generates 3,000,000 ids per strategy and reports:

- **1-thr op/s** — single-thread generation throughput.
- **8-thr op/s** — throughput with 8 concurrent "writers" (contention).
- **p50 / p99 / p99.9 ns** — per-call latency percentiles.
- **inv%** — adjacent inversions over 1,000,000 sequentially-generated ids in
  unsigned 16-byte order. **0 % = every insert appends to the B-tree right edge**
  (no fragmentation); ~50 % = random insert positions.

Strategies: UUIDv4 (random baseline), UUIDv7 with a per-ms random `rand_a`,
UUIDv7 with a **global lock-free monotonic** 12-bit counter (RFC 9562 method 1),
ULID per-ms random, and ULID **global monotonic** (spec monotonic factory).
The ULID encoder is validated against the published `01ARYZ6S41` reference
vector. Randomness uses `ThreadLocalRandom` — non-blocking, appropriate for a
non-secret surrogate key (see [findings](#findings)).

## Results

Measured on JDK 25.0.1, 11 cores (numbers vary by machine; **ratios and inv% are
the takeaways**, not absolute op/s):

| generator | 1-thr op/s | 8-thr op/s | p50 ns | p99 ns | p99.9 ns | inv% |
|---|---:|---:|---:|---:|---:|---:|
| UUIDv4 (random baseline) | 5,128,816 | 3,014,433 | 167 | 333 | 1,333 | **50.00%** |
| UUIDv7 (per-ms random) | 34,036,036 | 37,518,388 | 42 | 84 | 167 | 50.02% |
| **UUIDv7 (global lock-free monotonic)** | 23,690,320 | 4,747,650 | 83 | 84 | 167 | **0.00%** |
| ULID (per-ms random, base32) | 10,558,857 | 42,943,464 | 84 | 208 | 333 | 49.97% |
| ULID (global monotonic, base32) | 9,945,867 | 5,522,409 | 125 | 209 | 375 | 0.00% |

### Reading the matrix

1. **Both beat random UUIDv4 on generation** and, in monotonic form, drive
   inversions to **0 %** — random UUIDv4 sits at ~50 %, i.e. every second insert
   is a random-page write, and it is also the *only* generator that gets *slower*
   under contention (5.1M → 3.0M) because `SecureRandom` serializes. R-CORE-2's
   premise holds.
2. **Time-ordering is only ms-granular unless you make it monotonic.** The
   per-ms-random rows still show ~50 % inversions *within* a millisecond. Only
   the **monotonic** rows reach 0 % — a monotonic sub-ms counter is required to
   actually hit the fragmentation target, not just picking a v7/ULID type.
3. **On the non-contended and embarrassingly-parallel paths, UUIDv7 is 2–3×
   faster than ULID.** UUIDv7-random hits 34M/s single and 37.5M/s across 8
   threads; ULID-random manages 10.6M/s single because it pays a **base32
   encoding** tax (its 26-char canonical form) on every id. UUIDv7-monotonic is
   23.7M/s single vs ULID-monotonic's 9.9M/s.
4. **Global *strict* monotonicity is a single serialization point either way.**
   UUIDv7's 12-bit counter is lock-free (one CAS); ULID's 80-bit counter does not
   fit a CAS word and needs a lock. But under 8-way contention both converge to
   ~5M/s (4.7M UUIDv7-mono vs 5.5M ULID-mono) — the shared hotspot dominates, and
   a CAS retry-storm costs about the same as the lock here. So strict global
   monotonicity trades the 30M+ parallel path for ~5M/s. **The escape hatch is to
   shard the counter** (embed a node/worker id in a few bits): monotonic
   *per shard*, still ms-ordered globally, recovers the parallel throughput. See
   [follow-up](#follow-up-migration-story).
5. **Generation is never the write bottleneck.** Even the slowest monotonic
   option (~4.7M/s) clears the 100k-concurrent-writes target (R-CORE-2) with
   ~47× headroom; the DB round-trip dwarfs id generation. The choice is about
   **index locality, storage width, and native support**, not generation speed.

### Storage width (not in the harness, decisive)

| | Postgres column | on-disk width | native driver support |
|---|---|---|---|
| **UUIDv7** | native `uuid` | **16 bytes** | yes (`java.util.UUID`, JDBC `uuid`) |
| **ULID (canonical)** | `char(26)` / `text` | **26 bytes** | no — needs a codec |
| ULID (binary) | `uuid` / `bytea` | 16 bytes | loses the canonical string form |

The PK is duplicated across every secondary index; 10 extra bytes per row of
canonical ULID widens every one of them and lowers B-tree fanout. Storing ULID
as binary reclaims the width but throws away the only thing ULID has over
UUIDv7 (the readable 26-char string), leaving no advantage.

## Recommendation

**Adopt UUIDv7 with a global lock-free monotonic generator, stored in a native
Postgres `uuid` column.** It is the only candidate that is simultaneously
lock-free, strictly monotonic (0 % inversions → no index fragmentation), the
narrowest to store, and natively supported end to end by the JDBC/`uuid` stack —
directly satisfying R-CORE-2.

- **Generator:** the `Uuid7Mono` pattern in `PkBench.java` — `AtomicLong`
  packing `ts(48)|counter(12)`, one CAS per id, `ThreadLocalRandom` for the
  62-bit tail.
- **RNG:** `ThreadLocalRandom`, **not** `SecureRandom`. A surrogate PK is not a
  secret; `SecureRandom` blocks/contends — visible as UUIDv4 being the one
  generator that gets *slower* under contention (5.1M → 3.0M op/s). Non-blocking
  RNG keeps R-CORE-1's non-blocking promise.
- **ULID** is rejected: strictly worse generation, a lock in monotonic mode, no
  native Postgres type, and its one differentiator (readable string) is not
  worth +10 bytes across every index for an internal key.

### Mutiny / reactive integration

Id generation is a synchronous, CPU-only, **non-blocking** call — it must never
touch the DB, so the transaction cycle stays non-blocking (R-CORE-1). It slots
into a Mutiny pipeline as a plain supplier; **no `emitOn`/worker-pool offload**,
because there is nothing blocking to offload:

```java
@ApplicationScoped
public class ItemIdGenerator {
    private final AtomicLong state = new AtomicLong();   // ts(48)|counter(12)

    public UUID next() { /* CAS loop from PkBench.Uuid7Mono */ }
}

// on the reactive create path — generate inline, no scheduler hop:
Uni<Item> created = Uni.createFrom().item(idGen::next)
    .map(id -> new Item(id, ...))
    .flatMap(repo::persist);          // the only suspension point is the DB
```

Mutiny adds only a constant emitter cost over the raw generator, so the harness
measures the raw call and the pipeline inherits those numbers. This is why the
benchmark deliberately keeps Mutiny off the classpath.

## Follow-up migration story

This spike delivers the **decision and the generator pattern**, not a schema
change. A separate implementation story must:

1. Add the `ItemIdGenerator` (`@ApplicationScoped`) to the core module with unit
   tests for **strict monotonicity under concurrency** and **counter-overflow
   rollover** (>4096 ids in one ms borrows 1 ms — assert ordering holds).
2. Define the `uuid` PK columns + FKs in the initial schema/migration (there is
   no existing key to migrate at bootstrap — this is greenfield, so no
   data-backfill step is needed; the "migration" is schema definition only).
3. Decide whether to **shard the counter** (embed a node/worker id in a few
   random bits) so monotonic generation is per-shard: strict global monotonicity
   is a single hotspot that caps at ~4.7M op/s under contention, whereas the
   sharded/per-ms-random path sustains 30M+/s. At the 100k-write target the
   single hotspot is fine (~47× headroom), but sharding is the known escape hatch
   for horizontal scale and should be recorded in the identity ADR (ADR-VEC-08).
4. Load-test the full write path (id → persist → SSE) against the literal 100k
   concurrent-write R-CORE-2 target with a real Postgres, confirming index
   bloat/fragmentation stays flat over sustained inserts.
