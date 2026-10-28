package io.vectis.server;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The application with the {@code /api/v1} resources in it. The test configuration excludes
 * them, because they need the database and most server tests run without one; a test with a
 * database uses this profile, which clears the exclusion.
 */
public class ApiTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        // a type that does not exist: nothing is excluded
        return Map.of("quarkus.arc.exclude-types", "io.vectis.server.NoSuchType");
    }
}
