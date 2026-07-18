package io.vectis.core.extension;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Activates {@link PremiumTestAuditLogger} for a single test, simulating a
 * deployment where a higher-priority commercial audit plugin is on the
 * classpath.
 */
public class EnterpriseSelectedProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("vectis.test.premium.enabled", "true");
    }
}
