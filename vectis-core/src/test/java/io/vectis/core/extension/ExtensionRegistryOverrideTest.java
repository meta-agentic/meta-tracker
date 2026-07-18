package io.vectis.core.extension;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC #1: when a higher-priority implementation is discovered at runtime, the
 * registry selects it over the community floor with no core code change. The
 * floor remains present (outranked, not removed), which is what keeps the
 * discovery lookup non-empty when a commercial bean is later gated off.
 */
@QuarkusTest
@TestProfile(EnterpriseSelectedProfile.class)
class ExtensionRegistryOverrideTest {

    @Inject
    ExtensionRegistry registry;

    @Test
    void higherPriorityPluginWins() {
        assertEquals("premium-test", registry.activeAuditLogger().id());
    }

    @Test
    void communityFloorRemainsDiscoveredButOutranked() {
        var discovered = registry.discoveredAuditLoggerIds();
        assertEquals("premium-test", discovered.get(0));
        assertTrue(discovered.contains("community-local"));
        assertEquals(2L, registry.report().auditLoggerBeanCount());
    }
}
