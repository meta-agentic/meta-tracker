// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Turns the deployment's database settings into the settings of the two drivers that read them.
 *
 * <p>Vectis reaches PostgreSQL through two clients: the reactive pool serves requests and the JDBC
 * datasource exists for Flyway. Each has its own TLS vocabulary, and the two disagree on what
 * {@code require} means: the JDBC driver encrypts without checking the certificate, while the
 * reactive client checks the chain whenever it has trust material. A deployment therefore sets one
 * mode and this factory derives a consistent pair of driver settings from it:
 *
 * <ul>
 *   <li>{@code disable}: no TLS on either client;</li>
 *   <li>{@code require}: TLS on both, the certificate not checked by either;</li>
 *   <li>{@code verify-full}: TLS on both, the chain checked against {@code vectis.db.ssl-root-cert}
 *       and the host name checked against the certificate.</li>
 * </ul>
 *
 * <p>The root defaults to the operating system's CA bundle, which the distroless image ships, so a
 * database with a publicly trusted certificate needs no file of its own and neither client depends
 * on a JVM trust store, which a native executable does not have.
 *
 * <p>The JDBC half is a query string, {@value #JDBC_TLS_PARAMETERS}, that the production JDBC URL
 * appends, not a set of driver properties: with its own login Flyway opens connections from the
 * datasource's URL and the credentials alone, so anything outside the URL would not reach the
 * migrations.
 *
 * <p>It also gives Flyway its own login when one is configured, so the tables can be owned by a
 * role the running server does not use. The settings come from {@code VECTIS_DB_SSL_MODE},
 * {@code VECTIS_DB_SSL_ROOT_CERT}, {@code VECTIS_DB_MIGRATOR_USER} and
 * {@code VECTIS_DB_MIGRATOR_PASSWORD}. The derived properties sit above {@code application.properties}
 * and below the environment, so an explicit Quarkus variable still wins.
 */
public class DatabaseConnectionConfigSourceFactory implements ConfigSourceFactory {

    static final String SSL_MODE = "vectis.db.ssl-mode";
    static final String SSL_ROOT_CERT = "vectis.db.ssl-root-cert";
    static final String MIGRATOR_USER = "vectis.db.migrator-user";
    static final String MIGRATOR_PASSWORD = "vectis.db.migrator-password";

    static final String DEFAULT_SSL_ROOT_CERT = "/etc/ssl/certs/ca-certificates.crt";

    /** The JDBC URL's TLS query, outside {@code vectis.db} because it is derived, not set. */
    static final String JDBC_TLS_PARAMETERS = "vectis.derived.jdbc-tls-parameters";

    /** Above application.properties (250), below environment variables (300). */
    static final int ORDINAL = 260;

    static final String SOURCE_NAME = "vectis-database-connection";

    enum SslMode {
        DISABLE("disable"), REQUIRE("require"), VERIFY_FULL("verify-full");

        final String value;

        SslMode(String value) {
            this.value = value;
        }

        static SslMode parse(String raw) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (SslMode mode : values()) {
                if (mode.value.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException(SSL_MODE + " (VECTIS_DB_SSL_MODE) must be one of disable, require or"
                + " verify-full; received \"" + raw + "\"");
        }
    }

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        return List.of(new PropertiesConfigSource(derive(name -> value(context, name)), SOURCE_NAME, ORDINAL));
    }

    @Override
    public OptionalInt getPriority() {
        return OptionalInt.of(ORDINAL);
    }

    /**
     * The driver properties for the given settings. Package-private and free of the config context
     * so that every mode can be tested without starting an application.
     */
    static Map<String, String> derive(Settings settings) {
        Map<String, String> derived = new HashMap<>();
        String rawMode = settings.get(SSL_MODE);
        SslMode mode = rawMode == null || rawMode.isBlank() ? SslMode.DISABLE : SslMode.parse(rawMode);

        derived.put(JDBC_TLS_PARAMETERS, "sslmode=" + mode.value);
        derived.put("quarkus.datasource.reactive.postgresql.ssl-mode", mode.value);
        switch (mode) {
            case DISABLE -> { }
            case REQUIRE -> derived.put("quarkus.datasource.reactive.trust-all", "true");
            case VERIFY_FULL -> {
                String root = settings.get(SSL_ROOT_CERT);
                if (root == null || root.isBlank()) {
                    root = DEFAULT_SSL_ROOT_CERT;
                }
                if (!Files.isReadable(Path.of(root))) {
                    throw new IllegalArgumentException(SSL_ROOT_CERT + " (VECTIS_DB_SSL_ROOT_CERT) names \"" + root
                        + "\", which is not a readable file; verify-full needs the PEM bundle of the CAs that"
                        + " may sign the database's certificate");
                }
                derived.put(JDBC_TLS_PARAMETERS,
                    "sslmode=" + mode.value + "&sslrootcert=" + URLEncoder.encode(root, StandardCharsets.UTF_8));
                derived.put("quarkus.datasource.reactive.trust-certificate-pem.certs", root);
                derived.put("quarkus.datasource.reactive.hostname-verification-algorithm", "HTTPS");
            }
        }

        String migratorUser = blankToNull(settings.get(MIGRATOR_USER));
        String migratorPassword = blankToNull(settings.get(MIGRATOR_PASSWORD));
        if ((migratorUser == null) != (migratorPassword == null)) {
            throw new IllegalArgumentException("VECTIS_DB_MIGRATOR_USER and VECTIS_DB_MIGRATOR_PASSWORD are set together"
                + " or not at all; only " + (migratorUser == null ? "the password" : "the user") + " is set");
        }
        if (migratorUser != null) {
            derived.put("quarkus.flyway.username", migratorUser);
            derived.put("quarkus.flyway.password", migratorPassword);
        }
        return derived;
    }

    @FunctionalInterface
    interface Settings {
        String get(String name);
    }

    private static String value(ConfigSourceContext context, String name) {
        ConfigValue value = context.getValue(name);
        return value == null ? null : value.getValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
