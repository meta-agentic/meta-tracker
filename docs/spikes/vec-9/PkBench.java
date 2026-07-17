// VEC-9 — Time-Ordered Primary Key Performance Matrix (spike harness).
//
// Dependency-free benchmark comparing application-side generation of
// UUIDv4 (random baseline), UUIDv7 (RFC 9562) and ULID for use as the
// lock-free, time-ordered item identity required by ADR-VEC-01 R-CORE-2.
//
// Deliberately zero external dependencies so it runs on a bare JDK
// (single-file source launch) at the bootstrap stage, before the Maven
// build lands:  java docs/spikes/vec-9/PkBench.java
//
// It measures the things that decide the recommendation:
//   1. single-thread generation throughput / latency percentiles,
//   2. throughput under contention (8 concurrent "writers"),
//   3. index-insert locality (adjacent inversions in unsigned byte order) —
//      the proxy for B-tree append vs. random-page fragmentation, which is
//      what R-CORE-2's "without index fragmentation" target is really about.
//
// Mutiny is intentionally NOT on the classpath here: ID generation is a
// synchronous, CPU-only, non-blocking call, so wrapping it in a Uni/Multi
// adds only a constant emitter cost and does not change the comparison.
// The reactive integration pattern is documented in the spike report.

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.Arrays;

public class PkBench {

    // ---- generators ---------------------------------------------------

    interface Gen {
        String name();
        /** Emit one id as its 16-byte big-endian binary form (what a uuid/bytea column stores). */
        byte[] next();
    }

    static byte[] uuidToBytes(long msb, long lsb) {
        byte[] b = new byte[16];
        for (int i = 0; i < 8; i++) b[i]      = (byte) (msb >>> (56 - 8 * i));
        for (int i = 0; i < 8; i++) b[8 + i]  = (byte) (lsb >>> (56 - 8 * i));
        return b;
    }

    /** UUIDv4 — random, NOT time-ordered. The fragmentation baseline. */
    static final Gen UUID4 = new Gen() {
        public String name() { return "UUIDv4 (random baseline)"; }
        public byte[] next() {
            UUID u = UUID.randomUUID();               // SecureRandom-backed
            return uuidToBytes(u.getMostSignificantBits(), u.getLeastSignificantBits());
        }
    };

    /** UUIDv7 with a random rand_a — time-ordered to the millisecond, embarrassingly parallel. */
    static final Gen UUID7_RAND = new Gen() {
        public String name() { return "UUIDv7 (per-ms random)"; }
        public byte[] next() {
            long ts = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;   // 48 bits
            ThreadLocalRandom r = ThreadLocalRandom.current();
            long randA = r.nextInt() & 0xFFF;                          // 12 bits
            long msb = (ts << 16) | (0x7L << 12) | randA;
            long lsb = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variant 10 + 62b
            return uuidToBytes(msb, lsb);
        }
    };

    /**
     * UUIDv7 with a global, lock-free monotonic sub-ms counter (RFC 9562 method 1:
     * dedicated 12-bit counter in rand_a). A single AtomicLong packs ts(48)|counter(12);
     * one CAS per id guarantees strictly increasing keys across all threads with no lock.
     */
    static final class Uuid7Mono implements Gen {
        private final AtomicLong state = new AtomicLong(0);           // (ts << 12) | counter
        public String name() { return "UUIDv7 (global lock-free monotonic)"; }
        public byte[] next() {
            long now = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
            long next;
            while (true) {
                long cur = state.get();
                long curTs = cur >>> 12, curCtr = cur & 0xFFF;
                if (now > curTs)            next = now << 12;          // new ms, counter 0
                else if (curCtr < 0xFFF)    next = cur + 1;           // same/stale ms, bump counter
                else                        next = (curTs + 1) << 12; // counter exhausted, borrow 1ms
                if (state.compareAndSet(cur, next)) break;
            }
            long ts = next >>> 12, ctr = next & 0xFFF;
            long msb = (ts << 16) | (0x7L << 12) | ctr;
            long lsb = (ThreadLocalRandom.current().nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
            return uuidToBytes(msb, lsb);
        }
    }

    static final char[] B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray(); // Crockford
    /** Canonical ULID string: 48-bit time -> 10 chars, then 80-bit random -> 16 chars (two separate groups). */
    static String encodeUlid(byte[] b) {
        char[] out = new char[26];
        long ts = 0;
        for (int i = 0; i < 6; i++) ts = (ts << 8) | (b[i] & 0xFF);          // 48-bit time
        for (int g = 0; g < 10; g++) out[g] = B32[(int) ((ts >>> (45 - 5 * g)) & 0x1F)];
        long rTop = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);                    // rand bits 79..64
        long rBot = 0;
        for (int i = 8; i < 16; i++) rBot = (rBot << 8) | (b[i] & 0xFF);     // rand bits 63..0
        for (int g = 0; g < 16; g++) {
            int v = 0;
            for (int k = 0; k < 5; k++) {
                int bp = (79 - 5 * g) - k;
                int val = bp >= 64 ? (int) ((rTop >>> (bp - 64)) & 1) : (int) ((rBot >>> bp) & 1);
                v = (v << 1) | val;
            }
            out[10 + g] = B32[v];
        }
        return new String(out);
    }

    /** ULID (48-bit ts + 80-bit random), time-ordered, emitted as canonical 26-char string then measured as bytes. */
    static final class UlidRand implements Gen {
        public String name() { return "ULID (per-ms random, base32 encoded)"; }
        public byte[] next() {
            long ts = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            long hi = (ts << 16) | (r.nextInt() & 0xFFFF);           // 48 ts + top 16 rand
            long lo = r.nextLong();                                  // low 64 rand
            byte[] b = uuidToBytes(hi, lo);
            String s = encodeUlid(b);                                // realistic: canonical text form
            if (s.length() != 26) throw new IllegalStateException(s);
            return b;                                                // locality measured on the 128-bit value
        }
    }

    /**
     * ULID with a global monotonic 80-bit random increment (spec's monotonic factory).
     * The 80-bit counter does NOT fit a single CAS word, so this needs a lock — a real
     * structural difference from UUIDv7's lock-free 12-bit counter.
     */
    static final class UlidMono implements Gen {
        private long lastTs = -1;
        private long hiRand, loRand;                                 // 16 + 64 = 80 bits
        public String name() { return "ULID (global monotonic, base32 encoded)"; }
        public synchronized byte[] next() {
            long ts = System.currentTimeMillis() & 0xFFFFFFFFFFFFL;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (ts != lastTs) {
                lastTs = ts;
                hiRand = r.nextInt() & 0xFFFF;
                loRand = r.nextLong();
            } else {
                if (++loRand == 0) hiRand++;                         // 80-bit carry
            }
            long hi = (ts << 16) | (hiRand & 0xFFFF);
            byte[] b = uuidToBytes(hi, loRand);
            encodeUlid(b);
            return b;
        }
    }

    // ---- unsigned 16-byte comparison (index sort order) --------------

    static int cmp(byte[] a, byte[] b) {
        for (int i = 0; i < 16; i++) {
            int x = a[i] & 0xFF, y = b[i] & 0xFF;
            if (x != y) return x - y;
        }
        return 0;
    }

    // ---- benchmark driver --------------------------------------------

    static void bench(Gen g, int warmup, int measure, int threads) throws Exception {
        // warmup
        for (int i = 0; i < warmup; i++) g.next();

        // single-thread throughput — no per-iteration timer inside the window, so this
        // column is directly comparable to the multi-thread loop below.
        long t0 = System.nanoTime();
        for (int i = 0; i < measure; i++) g.next();
        long t1 = System.nanoTime();
        double stThroughput = measure / ((t1 - t0) / 1e9);

        // latency percentiles — measured in a separate, self-contained pass.
        int latN = Math.min(measure, 200_000);
        long[] lat = new long[latN];
        for (int i = 0; i < latN; i++) {
            long s = System.nanoTime();
            g.next();
            lat[i] = System.nanoTime() - s;
        }
        Arrays.sort(lat);
        long p50 = lat[(int) (latN * 0.50)];
        long p99 = lat[(int) (latN * 0.99)];
        long p999 = lat[(int) (latN * 0.999)];

        // multi-thread throughput under contention
        final int perThread = measure / threads;
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(threads);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < perThread; i++) g.next();
                done.countDown();
            });
            ts[t].start();
        }
        long m0 = System.nanoTime();
        start.countDown();
        done.await();
        long m1 = System.nanoTime();
        double mtThroughput = (long) perThread * threads / ((m1 - m0) / 1e9);

        // index-insert locality: adjacent inversions in generation order
        int localN = 1_000_000;
        byte[] prev = g.next();
        long inversions = 0;
        for (int i = 1; i < localN; i++) {
            byte[] cur = g.next();
            if (cmp(cur, prev) < 0) inversions++;
            prev = cur;
        }
        double invRate = 100.0 * inversions / localN;

        System.out.printf(
            "%-40s | %,10.0f | %,12.0f | %5d | %6d | %7d | %6.2f%%%n",
            g.name(), stThroughput, mtThroughput, p50, p99, p999, invRate);
    }

    public static void main(String[] args) throws Exception {
        int warmup = 500_000, measure = 3_000_000, threads = 8;
        System.out.println("JDK: " + System.getProperty("java.version")
            + "  cores: " + Runtime.getRuntime().availableProcessors()
            + "  measure/gen: " + String.format("%,d", measure)
            + "  contention threads: " + threads);
        System.out.printf("%-40s | %10s | %12s | %5s | %6s | %7s | %6s%n",
            "generator", "1-thr op/s", "8-thr op/s", "p50ns", "p99ns", "p99.9ns", "inv%");
        System.out.println("-".repeat(112));
        for (Gen g : new Gen[]{ UUID4, UUID7_RAND, new Uuid7Mono(),
                                new UlidRand(), new UlidMono() }) {
            bench(g, warmup, measure, threads);
        }
        System.out.println("-".repeat(112));
        System.out.println("inv% = adjacent inversions over 1,000,000 sequentially-generated ids "
            + "(0% = every insert appends to the B-tree right edge; higher = random-page writes = fragmentation).");
    }
}
