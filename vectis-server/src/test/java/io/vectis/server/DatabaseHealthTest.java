package io.vectis.server;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * VEC-101 (closes VEC-88 AC2): with a real database the three probes answer 200 and
 * readiness reports the reactive pool; once the database is gone readiness answers 503
 * while liveness and startup stay 200, so the pod leaves rotation but is not restarted.
 * Ordered, because the second test removes the database the first one needs.
 */
@QuarkusTest
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseHealthTest {

    @Test
    @Order(1)
    void withTheDatabaseUpTheProbesAnswer200AndReadinessNamesThePool() {
        for (String probe : new String[] {"started", "ready", "live"}) {
            given().accept("application/json").when().get("/q/health/" + probe)
                .then().statusCode(200).body("status", equalTo("UP"));
        }
        given().accept("application/json").when().get("/q/health/ready")
            .then().body("checks.name", hasItem("Reactive PostgreSQL connections health check"));
    }

    @Test
    @Order(2)
    void withTheDatabaseDownReadinessAnswers503WhileLivenessStays200() {
        DatabaseResource.takeDatabaseAway();

        given().accept("application/json").when().get("/q/health/ready")
            .then().statusCode(503).body("status", equalTo("DOWN"));
        given().accept("application/json").when().get("/q/health/live")
            .then().statusCode(200).body("status", equalTo("UP"));
        given().accept("application/json").when().get("/q/health/started")
            .then().statusCode(200).body("status", equalTo("UP"));
    }
}
