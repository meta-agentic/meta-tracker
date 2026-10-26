package io.vectis.server;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Makes {@link DatabaseConfig} count as used (VEC-101). Quarkus loads and validates a
 * configuration mapping only when a bean injects it, so without this class an unset
 * {@code VECTIS_DB_PASSWORD} would not fail the boot. The check itself happens when the
 * configuration loads, before the datasource connects.
 */
@Startup
@ApplicationScoped
public class DatabasePasswordGuard {

    @Inject
    DatabaseConfig config;
}
