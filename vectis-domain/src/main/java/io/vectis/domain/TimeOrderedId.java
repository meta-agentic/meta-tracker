package io.vectis.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UUIDv7 generation — a 48-bit big-endian Unix timestamp in milliseconds followed by
 * random bits, per RFC 9562 §5.7.
 *
 * <p>Vectis generates primary keys application-side rather than from a database
 * sequence. The identity benchmark recorded the reason: a central sequence serialises
 * concurrent inserts on a single hot page, and random UUIDv4 keys scatter B-tree
 * writes across the whole index. A time-ordered key gives both index locality and a
 * natural chronological sort, so "newest items" needs no secondary index and bulk
 * planning sessions do not contend on one page.
 *
 * <h2>Monotonicity</h2>
 *
 * <p>Timestamp resolution is one millisecond, and a planning session can easily create
 * several items inside one. RFC 9562 §6.2 method 1 is used: the 12 bits of {@code rand_a}
 * carry a counter that is seeded randomly when the millisecond changes and incremented
 * for each subsequent identifier within the same millisecond. Two identifiers minted in
 * the same millisecond therefore still order by creation, which a purely random
 * {@code rand_a} would not guarantee.
 *
 * <p>The counter is seeded into the low half of its range so that a burst has room to
 * increment without overflowing. In the rare event it saturates, generation waits for
 * the next millisecond rather than wrapping — wrapping would silently produce an
 * identifier that sorts <em>before</em> its predecessor, which is precisely the
 * guarantee this class exists to provide.
 *
 * <p>Instances are thread-safe.
 */
public final class TimeOrderedId {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** {@code rand_a} is 12 bits, so the counter saturates at 4095. */
    private static final int COUNTER_MAX = 0xFFF;

    /** Packs the last-used millisecond and counter so both advance in one CAS. */
    private final AtomicLong state = new AtomicLong(0);

    private static final TimeOrderedId INSTANCE = new TimeOrderedId();

    private TimeOrderedId() {
    }

    /** Shared generator. */
    public static TimeOrderedId generator() {
        return INSTANCE;
    }

    /** Mints the next identifier, never sorting before one previously returned. */
    public static UUID next() {
        return INSTANCE.generate();
    }

    UUID generate() {
        long millis;
        int counter;

        while (true) {
            long now = System.currentTimeMillis();
            long prev = state.get();
            long prevMillis = prev >>> 13;
            int prevCounter = (int) (prev & 0x1FFF);

            if (now > prevMillis) {
                // New millisecond: reseed the counter in the low half of its range.
                counter = RANDOM.nextInt(COUNTER_MAX >>> 1);
                millis = now;
            } else {
                // Same millisecond (or a clock that stepped backwards): keep climbing.
                millis = prevMillis;
                counter = prevCounter + 1;
                if (counter > COUNTER_MAX) {
                    // Saturated. Wait for the next millisecond rather than wrap, which
                    // would emit an identifier that sorts before its predecessor.
                    Thread.onSpinWait();
                    continue;
                }
            }

            if (state.compareAndSet(prev, (millis << 13) | counter)) {
                return assemble(millis, counter);
            }
        }
    }

    private static UUID assemble(long millis, int counter) {
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16   // 48-bit timestamp
                | (0x7L << 12)                          // version 7
                | (counter & 0xFFFL);                   // rand_a as monotonic counter

        long lsb = RANDOM.nextLong();
        lsb &= 0x3FFF_FFFF_FFFF_FFFFL;                  // clear the two variant bits
        lsb |= 0x8000_0000_0000_0000L;                  // RFC 9562 variant b10

        return new UUID(msb, lsb);
    }

    /** The creation instant encoded in a UUIDv7's leading 48 bits. */
    public static Instant timestampOf(UUID id) {
        if (id.version() != 7) {
            throw new IllegalArgumentException("not a UUIDv7: " + id + " (version " + id.version() + ")");
        }
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }
}
