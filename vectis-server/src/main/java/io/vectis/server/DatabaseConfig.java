package io.vectis.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;

import java.util.Optional;

/**
 * The deployment's database settings under {@code vectis.db}.
 *
 * <p>The password is a required value (VEC-101). Quarkus treats the datasource password as
 * optional, so an unset {@code VECTIS_DB_PASSWORD} would connect without one. Mapping it here makes
 * the configuration fail to load, naming {@code vectis.db.password}, when the production profile has
 * no non-empty password.
 *
 * <p>The TLS and migration-login settings are read when the configuration is built, by
 * {@link DatabaseConnectionConfigSourceFactory}, which turns them into the drivers' own settings.
 * They are declared here so that the configuration recognises them.
 */
@ConfigMapping(prefix = "vectis.db")
public interface DatabaseConfig {

    String password();

    /** {@code disable}, {@code require} or {@code verify-full}; {@code disable} when absent. */
    @WithName("ssl-mode")
    Optional<String> sslMode();

    /** The PEM bundle {@code verify-full} trusts; the operating system's CA bundle when absent. */
    @WithName("ssl-root-cert")
    Optional<String> sslRootCert();

    /** Flyway's own login, set together with {@link #migratorPassword()}. */
    @WithName("migrator-user")
    Optional<String> migratorUser();

    @WithName("migrator-password")
    Optional<String> migratorPassword();
}
