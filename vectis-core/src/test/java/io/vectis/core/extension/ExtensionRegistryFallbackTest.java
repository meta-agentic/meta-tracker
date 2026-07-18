package io.vectis.core.extension;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AC #2: with no commercial plugin on the classpath, the container still
 * builds, the registry resolves, and every extension point falls back to its
 * community default with no DI fault.
 */
@QuarkusTest
class ExtensionRegistryFallbackTest {

    @Inject
    ExtensionRegistry registry;

    @Test
    void auditLoggerFallsBackToCommunity() {
        assertEquals("community-local", registry.activeAuditLogger().id());
        assertEquals(java.util.List.of("community-local"),
                registry.discoveredAuditLoggerIds());
    }

    @Test
    void syncConnectorFallsBackToDisabledNoop() {
        assertEquals("community-none", registry.activeSyncConnector().id());
        assertFalse(registry.activeSyncConnector().available());
    }

    @Test
    void reportShowsCommunityFloorAsSoleBean() {
        var report = registry.report();
        assertEquals("community-local", report.activeAuditLogger());
        assertEquals(1L, report.auditLoggerBeanCount());
        assertFalse(report.syncAvailable());
    }
}
