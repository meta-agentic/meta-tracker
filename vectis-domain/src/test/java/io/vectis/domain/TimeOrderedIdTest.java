package io.vectis.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TimeOrderedIdTest {

    @Test
    void mintsVersion7WithRfcVariant() {
        UUID id = TimeOrderedId.next();
        assertEquals(7, id.version());
        assertEquals(2, id.variant(), "RFC 9562 variant b10");
    }

    @Test
    void encodesCreationTimeInTheLeadingBits() {
        Instant before = Instant.now().minusMillis(1);
        UUID id = TimeOrderedId.next();
        Instant after = Instant.now().plusMillis(1);

        Instant stamp = TimeOrderedId.timestampOf(id);
        assertFalse(stamp.isBefore(before), "timestamp " + stamp + " precedes " + before);
        assertFalse(stamp.isAfter(after), "timestamp " + stamp + " follows " + after);
    }

    @Test
    void rejectsNonV7WhenReadingTheTimestamp() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> TimeOrderedId.timestampOf(UUID.randomUUID()));
        assertTrue(thrown.getMessage().contains("not a UUIDv7"), thrown.getMessage());
    }

    /**
     * The guarantee that matters: a burst inside a single millisecond must still sort by
     * creation order. A purely random {@code rand_a} would satisfy a uniqueness assertion
     * and fail this one.
     */
    @Test
    void isMonotonicWithinTheSameMillisecond() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            ids.add(TimeOrderedId.next());
        }

        List<String> asGenerated = ids.stream().map(UUID::toString).toList();
        List<String> sorted = asGenerated.stream().sorted().toList();

        assertEquals(sorted, asGenerated, "lexicographic order must match generation order");
    }

    @Test
    void isUniqueUnderConcurrentGeneration() throws Exception {
        int threads = 8;
        int perThread = 2_000;
        var seen = new ConcurrentSkipListSet<UUID>();
        var start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        seen.add(TimeOrderedId.next());
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "generation did not finish in time");
        }

        assertEquals(threads * perThread, seen.size());
    }
}
