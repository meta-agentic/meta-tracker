package io.vectis.plugin.audit.enterprise;

import io.vectis.extension.spi.AuditEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Plain unit test — no CDI container. Proves the plugin's logic works against
 * the SPI contract alone, which is also evidence of AC #3: the plugin needs
 * nothing from vectis-core or vectis-server to compile and run.
 */
class TamperEvidentAuditLoggerTest {

    @Test
    void reportsStableId() {
        assertEquals("enterprise-tamper-evident", new TamperEvidentAuditLogger().id());
    }

    @Test
    void chainHeadAdvancesAndIsOrderDependent() {
        var a = new TamperEvidentAuditLogger();
        String genesis = a.chainHead();
        a.record(AuditEvent.of("t1", "alice", "board.item.moved"));
        String afterFirst = a.chainHead();
        a.record(AuditEvent.of("t1", "bob", "board.item.created"));
        String afterSecond = a.chainHead();

        assertNotEquals(genesis, afterFirst);
        assertNotEquals(afterFirst, afterSecond);

        // Same two events in the opposite order yield a different head:
        // the chain binds each event to its predecessor.
        var b = new TamperEvidentAuditLogger();
        b.record(AuditEvent.of("t1", "bob", "board.item.created"));
        b.record(AuditEvent.of("t1", "alice", "board.item.moved"));
        assertNotEquals(afterSecond, b.chainHead());
    }
}
