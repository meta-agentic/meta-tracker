package io.vectis.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

/**
 * A real PostgreSQL for the tests that need the database (VEC-101), switching the
 * datasource back on and pointing both clients at the container. The test can stop it
 * with {@link #takeDatabaseAway()} to take the database away from a running server.
 */
public class DatabaseResource implements QuarkusTestResourceLifecycleManager {

    /** Same minor line as CI and deploy/k8s/components/postgres. */
    private static final String IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer postgres;

    @Override
    @SuppressWarnings("resource")
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer(IMAGE);
        postgres.start();
        String address = postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName();
        return Map.of(
            "quarkus.datasource.active", "true",
            "quarkus.datasource.username", postgres.getUsername(),
            "quarkus.datasource.password", postgres.getPassword(),
            "quarkus.datasource.jdbc.url", "jdbc:postgresql://" + address,
            "quarkus.datasource.reactive.url", "postgresql://" + address);
    }

    /** Takes the database away; the container is gone, not paused. */
    static void takeDatabaseAway() {
        postgres.stop();
    }

    @Override
    public void stop() {
        postgres.stop();
    }
}
