// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/**
 * The server against a PostgreSQL that serves TLS, configured only through the deployment's settings
 * ({@code vectis.db.ssl-mode=verify-full} and the run's CA as {@code vectis.db.ssl-root-cert}), with the
 * JDBC URL ending in the derived TLS query as the production URL does. The database also accepts plain
 * connections, so a driver that ignored the setting would still connect, and the test would see it in
 * {@code pg_stat_ssl}.
 */
public class TlsDatabaseResource implements QuarkusTestResourceLifecycleManager {

    private TlsPostgres postgres;

    @Override
    public Map<String, String> start() {
        postgres = TlsPostgres.start();
        return Map.of(
            "quarkus.datasource.active", "true",
            "quarkus.datasource.username", postgres.container.getUsername(),
            "quarkus.datasource.password", postgres.container.getPassword(),
            "quarkus.datasource.jdbc.url", postgres.jdbcUrl("localhost") + "?${vectis.derived.jdbc-tls-parameters}",
            "quarkus.datasource.reactive.url", postgres.reactiveUrl("localhost"),
            "vectis.db.ssl-mode", "verify-full",
            "vectis.db.ssl-root-cert", postgres.caCertificate.toString());
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.close();
        }
    }
}
