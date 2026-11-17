// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server boots against a TLS database with {@code verify-full} set through the deployment's
 * settings alone, and both of its clients really use TLS: Flyway's JDBC connection and the reactive
 * pool that serves requests. Each connection asks the server whether it is encrypted.
 */
@QuarkusTest
@QuarkusTestResource(value = TlsDatabaseResource.class, restrictToAnnotatedClass = true)
class DatabaseTlsTest {

    private static final String IS_ENCRYPTED = "select ssl from pg_stat_ssl where pid = pg_backend_pid()";

    // Lazily: the other test applications run without a database, where these beans are inactive and
    // a plain injection point would stop their boot.
    @Inject
    Instance<Pool> pool;

    @Inject
    Instance<AgroalDataSource> dataSource;

    @Test
    void theServerBootsAndReadinessIsUp() {
        given().accept("application/json").when().get("/q/health/ready")
            .then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void theReactivePoolIsEncrypted() {
        Row row = pool.get().query(IS_ENCRYPTED).execute().await().atMost(Duration.ofSeconds(10)).iterator().next();

        assertTrue(row.getBoolean("ssl"), "the reactive pool's connection should be encrypted");
    }

    @Test
    void theJdbcDatasourceFlywayMigratesThroughIsEncrypted() throws SQLException {
        try (Connection connection = dataSource.get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(IS_ENCRYPTED)) {
            assertTrue(result.next() && result.getBoolean("ssl"), "the JDBC connection should be encrypted");
        }
    }
}
