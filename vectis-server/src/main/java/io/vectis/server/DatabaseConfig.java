package io.vectis.server;

import io.smallrye.config.ConfigMapping;

/**
 * The database password, as a required value (VEC-101). Quarkus treats the datasource password
 * as optional, so an unset {@code VECTIS_DB_PASSWORD} would connect without one. Mapping it
 * here makes the configuration fail to load, naming {@code vectis.db.password}, when the
 * production profile has no non-empty password.
 */
@ConfigMapping(prefix = "vectis.db")
public interface DatabaseConfig {

    String password();
}
