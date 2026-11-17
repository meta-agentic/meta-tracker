// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The driver settings each database setting turns into, without starting an application. */
class DatabaseConnectionConfigSourceFactoryTest {

    @TempDir
    Path directory;

    private static Map<String, String> derive(Map<String, String> settings) {
        return DatabaseConnectionConfigSourceFactory.derive(settings::get);
    }

    private static void assertMessageNames(Exception e, String... parts) {
        for (String part : parts) {
            assertTrue(e.getMessage().contains(part), () -> "\"" + e.getMessage() + "\" should name " + part);
        }
    }

    @Test
    void withNothingSetBothDriversRunWithoutTls() {
        assertEquals(Map.of(
            "vectis.derived.jdbc-tls-parameters", "sslmode=disable",
            "quarkus.datasource.reactive.postgresql.ssl-mode", "disable"), derive(Map.of()));
    }

    @Test
    void requireEncryptsOnBothDriversAndChecksNoCertificateOnEither() {
        assertEquals(Map.of(
            "vectis.derived.jdbc-tls-parameters", "sslmode=require",
            "quarkus.datasource.reactive.postgresql.ssl-mode", "require",
            "quarkus.datasource.reactive.trust-all", "true"), derive(Map.of("vectis.db.ssl-mode", "require")));
    }

    @Test
    void verifyFullChecksChainAndHostNameOnBothDriversAgainstTheGivenBundle() throws IOException {
        Path bundle = Files.writeString(directory.resolve("ca.pem"), "not parsed here");

        assertEquals(Map.of(
                "vectis.derived.jdbc-tls-parameters",
                "sslmode=verify-full&sslrootcert=" + URLEncoder.encode(bundle.toString(), StandardCharsets.UTF_8),
                "quarkus.datasource.reactive.postgresql.ssl-mode", "verify-full",
                "quarkus.datasource.reactive.trust-certificate-pem.certs", bundle.toString(),
                "quarkus.datasource.reactive.hostname-verification-algorithm", "HTTPS"),
            derive(Map.of("vectis.db.ssl-mode", "VERIFY-FULL ", "vectis.db.ssl-root-cert", bundle.toString())));
    }

    @Test
    void verifyFullWithoutABundleTrustsTheOperatingSystemCas() {
        Map<String, String> settings = Map.of("vectis.db.ssl-mode", "verify-full");
        String bundle = DatabaseConnectionConfigSourceFactory.DEFAULT_SSL_ROOT_CERT;

        if (Files.isReadable(Path.of(bundle))) {
            assertEquals(bundle, derive(settings).get("quarkus.datasource.reactive.trust-certificate-pem.certs"));
            assertEquals("sslmode=verify-full&sslrootcert=" + URLEncoder.encode(bundle, StandardCharsets.UTF_8),
                derive(settings).get("vectis.derived.jdbc-tls-parameters"));
        } else {
            assertMessageNames(assertThrows(IllegalArgumentException.class, () -> derive(settings)), "VECTIS_DB_SSL_ROOT_CERT");
        }
    }

    @Test
    void verifyFullWithAnUnreadableBundleFailsNamingTheVariable() {
        String missing = directory.resolve("absent.pem").toString();

        assertMessageNames(assertThrows(IllegalArgumentException.class,
                () -> derive(Map.of("vectis.db.ssl-mode", "verify-full", "vectis.db.ssl-root-cert", missing))),
            "VECTIS_DB_SSL_ROOT_CERT", missing);
    }

    @Test
    void anUnknownModeFailsNamingTheVariableAndTheAllowedValues() {
        assertMessageNames(assertThrows(IllegalArgumentException.class,
                () -> derive(Map.of("vectis.db.ssl-mode", "prefer"))),
            "VECTIS_DB_SSL_MODE", "disable, require or verify-full", "prefer");
    }

    @Test
    void aMigratorLoginBecomesFlywaysOwnCredentials() {
        Map<String, String> derived = derive(Map.of("vectis.db.migrator-user", "owner", "vectis.db.migrator-password", "secret"));

        assertEquals("owner", derived.get("quarkus.flyway.username"));
        assertEquals("secret", derived.get("quarkus.flyway.password"));
    }

    @Test
    void halfAMigratorLoginFailsNamingWhatIsMissing() {
        assertMessageNames(assertThrows(IllegalArgumentException.class,
            () -> derive(Map.of("vectis.db.migrator-user", "owner"))), "only the user is set");
        assertMessageNames(assertThrows(IllegalArgumentException.class,
            () -> derive(Map.of("vectis.db.migrator-password", "secret"))), "only the password is set");
    }

    @Test
    void withoutAMigratorLoginFlywayKeepsTheDatasourceCredentials() {
        Map<String, String> derived = derive(Map.of("vectis.db.migrator-user", " ", "vectis.db.migrator-password", ""));

        assertFalse(derived.containsKey("quarkus.flyway.username"));
        assertFalse(derived.containsKey("quarkus.flyway.password"));
    }
}
