package io.vectis.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * AC #2 at the application level: the composition root has no commercial plugin
 * on its classpath (default build), yet the whole Quarkus app boots and serves.
 * The diagnostics endpoint proves the active audit logger is the community
 * floor and external sync is off — no DI fault, no missing-bean failure.
 */
@QuarkusTest
class ServerBootFallbackTest {

    @Test
    void appBootsAndServesCommunityExtensions() {
        given()
            .when().get("/api/extensions")
            .then()
                .statusCode(200)
                .body("activeAuditLogger", equalTo("community-local"))
                .body("syncAvailable", is(false))
                .body("activeSyncConnector", equalTo("community-none"));
    }
}
