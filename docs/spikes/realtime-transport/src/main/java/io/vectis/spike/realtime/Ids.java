package io.vectis.spike.realtime;

import java.security.SecureRandom;
import java.util.UUID;

/** UUIDv7 (RFC 9562), as the schema's identifiers are; without vectis-domain's monotonic counter. */
final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    static UUID next() {
        long millis = System.currentTimeMillis();
        long high = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
        long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low);
    }
}
