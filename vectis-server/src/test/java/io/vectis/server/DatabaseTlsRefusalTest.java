// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.vertx.core.Vertx;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgConnection;
import io.vertx.pgclient.SslMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Both drivers, configured from exactly what {@link DatabaseConnectionConfigSourceFactory} derives (the
 * JDBC driver through the URL query, as Flyway and the datasource receive it),
 * against a TLS database whose certificate names {@code localhost} and is signed by a CA of the run:
 * {@code verify-full} connects with that CA and that name, and refuses a host name the certificate
 * does not carry and a CA that did not sign it; {@code require} connects without checking either.
 *
 * <p>The reactive options are built the way Quarkus's reactive PostgreSQL recorder builds them from
 * the same properties (ssl mode, trust-all, PEM trust, host-name verification algorithm).
 */
class DatabaseTlsRefusalTest {

    private static TlsPostgres postgres;
    private static Vertx vertx;

    @BeforeAll
    static void start() {
        postgres = TlsPostgres.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stop() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (postgres != null) {
            postgres.close();
        }
    }

    private static Map<String, String> verifyFullWith(Path bundle) {
        return DatabaseConnectionConfigSourceFactory.derive(
            Map.of("vectis.db.ssl-mode", "verify-full", "vectis.db.ssl-root-cert", bundle.toString())::get);
    }

    private static boolean jdbcIsEncrypted(String host, Map<String, String> derived) throws SQLException {
        String url = postgres.jdbcUrl(host) + "?" + derived.get(DatabaseConnectionConfigSourceFactory.JDBC_TLS_PARAMETERS);
        try (Connection connection = DriverManager.getConnection(url, postgres.container.getUsername(),
                 postgres.container.getPassword());
             ResultSet result = connection.createStatement()
                 .executeQuery("select ssl from pg_stat_ssl where pid = pg_backend_pid()")) {
            return result.next() && result.getBoolean(1);
        }
    }

    private static boolean reactiveIsEncrypted(String host, Map<String, String> derived) throws Exception {
        PgConnectOptions options = new PgConnectOptions()
            .setHost(host)
            .setPort(postgres.port())
            .setDatabase(postgres.database())
            .setUser(postgres.container.getUsername())
            .setPassword(postgres.container.getPassword())
            .setSslMode(SslMode.of(derived.get("quarkus.datasource.reactive.postgresql.ssl-mode")))
            .setTrustAll(Boolean.parseBoolean(derived.getOrDefault("quarkus.datasource.reactive.trust-all", "false")));
        String certs = derived.get("quarkus.datasource.reactive.trust-certificate-pem.certs");
        if (certs != null) {
            options.setTrustOptions(new PemTrustOptions().addCertPath(certs));
        }
        options.setHostnameVerificationAlgorithm(
            derived.getOrDefault("quarkus.datasource.reactive.hostname-verification-algorithm", ""));
        PgConnection connection = PgConnection.connect(vertx, options)
            .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        try {
            return connection.query("select ssl from pg_stat_ssl where pid = pg_backend_pid()").execute()
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
                .iterator().next().getBoolean(0);
        } finally {
            connection.close();
        }
    }

    @Test
    void verifyFullConnectsBothDriversWithTheSigningCaAndTheCertifiedName() throws Exception {
        Map<String, String> derived = verifyFullWith(postgres.caCertificate);

        assertTrue(jdbcIsEncrypted("localhost", derived));
        assertTrue(reactiveIsEncrypted("localhost", derived));
    }

    @Test
    void verifyFullRefusesAHostNameTheCertificateDoesNotCarry() {
        Map<String, String> derived = verifyFullWith(postgres.caCertificate);

        assertThrows(SQLException.class, () -> jdbcIsEncrypted("127.0.0.1", derived));
        assertThrows(ExecutionException.class, () -> reactiveIsEncrypted("127.0.0.1", derived));
    }

    @Test
    void verifyFullRefusesACaThatDidNotSignTheCertificate() {
        Path publicCas = Path.of(DatabaseConnectionConfigSourceFactory.DEFAULT_SSL_ROOT_CERT);
        assumeTrue(Files.isReadable(publicCas), "this machine has no operating system CA bundle");
        Map<String, String> derived = verifyFullWith(publicCas);

        assertThrows(SQLException.class, () -> jdbcIsEncrypted("localhost", derived));
        assertThrows(ExecutionException.class, () -> reactiveIsEncrypted("localhost", derived));
    }

    @Test
    void requireEncryptsWithoutCheckingTheCertificate() throws Exception {
        Map<String, String> derived = DatabaseConnectionConfigSourceFactory.derive(Map.of("vectis.db.ssl-mode", "require")::get);

        assertTrue(jdbcIsEncrypted("127.0.0.1", derived));
        assertTrue(reactiveIsEncrypted("127.0.0.1", derived));
    }
}
