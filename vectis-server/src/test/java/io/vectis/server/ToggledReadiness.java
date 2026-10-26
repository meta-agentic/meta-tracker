package io.vectis.server;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test stand-in for a dependency that goes away (VEC-88). The server has no
 * datasource of its own yet, so this is what lets a test pin that a failing
 * readiness check answers 503 on {@code /ready} while {@code /live} stays 200.
 */
@Readiness
@ApplicationScoped
public class ToggledReadiness implements HealthCheck {

    static final AtomicBoolean DEPENDENCY_UP = new AtomicBoolean(true);

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("toggled-dependency").status(DEPENDENCY_UP.get()).build();
    }
}
