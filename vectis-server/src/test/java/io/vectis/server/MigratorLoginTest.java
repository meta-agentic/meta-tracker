// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.mutiny.sqlclient.Row;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a migration login configured, Flyway migrates as that login and owns every table, and the
 * server runs as a login with no DDL right that still reads and writes the data.
 */
@QuarkusTest
@QuarkusTestResource(value = MigratorDatabaseResource.class, restrictToAnnotatedClass = true)
class MigratorLoginTest {

    // Lazily: the other test applications run without a database, where the pool is inactive and a
    // plain injection point would stop their boot.
    @Inject
    Instance<Pool> pool;

    private Row first(String sql) {
        return pool.get().query(sql).execute().await().atMost(Duration.ofSeconds(10)).iterator().next();
    }

    @Test
    void theServerBootsAndReadinessIsUp() {
        given().accept("application/json").when().get("/q/health/ready")
            .then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void theMigrationLoginOwnsEveryTable() {
        Set<String> owners = new HashSet<>();
        pool.get().query("select tableowner from pg_tables where schemaname = 'public'").execute()
            .await().atMost(Duration.ofSeconds(10)).forEach(row -> owners.add(row.getString(0)));

        assertEquals(Set.of(MigratorDatabaseResource.MIGRATOR), owners);
    }

    @Test
    void theServerRunsAsTheRuntimeLoginAndReadsTheMigratedData() {
        assertEquals(MigratorDatabaseResource.RUNTIME, first("select current_user").getString(0));
        assertTrue(first("select count(*) from workflow_template").getLong(0) > 0,
            "the runtime login should read the templates the migrations seeded");
    }

    @Test
    void theRuntimeLoginCannotChangeTheSchema() {
        assertThrows(RuntimeException.class,
            () -> pool.get().query("create table not_allowed (id int)").execute().await().atMost(Duration.ofSeconds(10)));
    }
}
