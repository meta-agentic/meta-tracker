// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VEC-68 AC6 (as amended for Cloud Run): the server built with the template hierarchy migrations
 * boots against PostgreSQL 16, migrated from empty at start, with the four built-in templates
 * published, and reports ready.
 *
 * <p>The database is read over plain JDBC rather than an injected pool: injecting the pool here
 * would make it a bean of every test's application, including those that run with the
 * datasource switched off.
 */
@QuarkusTest
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class TemplateTablesBootTest {

    @Test
    void theServerBootsOnTheTemplateHierarchySchemaWithTheBuiltInsPublished() throws SQLException {
        given().accept("application/json").when().get("/q/health/ready")
            .then().statusCode(200).body("status", equalTo("UP"));

        Config config = ConfigProvider.getConfig();
        List<String> builtIns = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                 select t.key || '@' || v.version
                   from workflow_template t join workflow_template_version v on v.template_id = t.id
                  where t.tenant_id is null
                  order by t.key, v.version
                 """)) {
            while (rs.next()) {
                builtIns.add(rs.getString(1));
            }
        }
        assertEquals(List.of("base@1", "hybrid@1", "kanban@1", "scrum@1"), builtIns);
    }
}
