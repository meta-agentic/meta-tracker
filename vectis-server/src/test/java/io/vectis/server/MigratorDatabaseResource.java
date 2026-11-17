// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * A database prepared the way a managed deployment prepares one: a migration login that owns the
 * database and every table it creates, and a runtime login that owns nothing and receives, by
 * default privilege, only the data rights it needs. The server is given the runtime login as its
 * datasource and the migration login through {@code vectis.db.migrator-*} alone.
 *
 * <p>It serves TLS and the server checks it with {@code verify-full}, because with its own login
 * Flyway builds its connections from the URL and the credentials alone: this is the path on which
 * the TLS query must travel in the URL.
 */
public class MigratorDatabaseResource implements QuarkusTestResourceLifecycleManager {

    static final String MIGRATOR = "vectis_migrator";
    static final String RUNTIME = "vectis_app";
    private static final String DATABASE = "vectis_split";
    private static final String MIGRATOR_PASSWORD = "migrator-test-password";
    private static final String RUNTIME_PASSWORD = "runtime-test-password";

    private TlsPostgres postgres;

    @Override
    public Map<String, String> start() {
        postgres = TlsPostgres.start();
        try {
            run(postgres.container.getDatabaseName(),
                "create role " + MIGRATOR + " login password '" + MIGRATOR_PASSWORD + "'",
                "create role " + RUNTIME + " login password '" + RUNTIME_PASSWORD + "'",
                "create database " + DATABASE + " owner " + MIGRATOR);
            run(DATABASE,
                "alter default privileges for role " + MIGRATOR + " in schema public"
                    + " grant select, insert, update, delete on tables to " + RUNTIME,
                "alter default privileges for role " + MIGRATOR + " in schema public"
                    + " grant usage, select on sequences to " + RUNTIME);
        } catch (SQLException e) {
            throw new IllegalStateException("could not prepare the two logins", e);
        }
        String address = "localhost:" + postgres.port() + "/" + DATABASE;
        return Map.of(
            "quarkus.datasource.active", "true",
            "quarkus.datasource.username", RUNTIME,
            "quarkus.datasource.password", RUNTIME_PASSWORD,
            "quarkus.datasource.jdbc.url", "jdbc:postgresql://" + address + "?${vectis.derived.jdbc-tls-parameters}",
            "quarkus.datasource.reactive.url", "postgresql://" + address,
            "vectis.db.ssl-mode", "verify-full",
            "vectis.db.ssl-root-cert", postgres.caCertificate.toString(),
            "vectis.db.migrator-user", MIGRATOR,
            "vectis.db.migrator-password", MIGRATOR_PASSWORD);
    }

    private void run(String database, String... statements) throws SQLException {
        String url = "jdbc:postgresql://localhost:" + postgres.port() + "/" + database;
        try (Connection connection = DriverManager.getConnection(url, postgres.container.getUsername(),
                 postgres.container.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.close();
        }
    }
}
