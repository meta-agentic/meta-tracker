package io.vectis.server;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * VEC-88: the three probes {@code deploy/k8s/base} calls answer 200 with the
 * standard SmallRye JSON, and the SPA history fallback never turns them into
 * the shell, even for a browser's {@code Accept: text/html}. They are public:
 * the server has no authentication yet, and VEC-35 keeps these paths open.
 */
@QuarkusTest
class HealthEndpointsTest {

    @ParameterizedTest
    @ValueSource(strings = {"/q/health/started", "/q/health/ready", "/q/health/live", "/q/health"})
    void probeAnswers200WithTheStandardJson(String path) {
        given().accept("application/json").when().get(path)
            .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("status", equalTo("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/q/health/started", "/q/health/ready", "/q/health/live", "/q/health"})
    void probeIsNeverRewrittenToTheShell(String path) {
        given().accept("text/html").when().get(path)
            .then()
                .statusCode(200)
                .contentType(not(startsWith("text/html")))
                .body(not(containsString(SpaOneOriginTest.SHELL_MARKER)))
                .body("status", equalTo("UP"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/q/health/started", "/q/health/ready", "/q/health/live"})
    void headOnAProbeIsNotTheShell(String path) {
        given().accept("text/html").when().head(path)
            .then()
                .statusCode(200)
                .contentType(not(startsWith("text/html")));
    }

    @Test
    void failingReadinessAnswers503WhileLivenessStays200() {
        ToggledReadiness.DEPENDENCY_UP.set(false);
        try {
            given().accept("application/json").when().get("/q/health/ready")
                .then().statusCode(503).body("status", equalTo("DOWN"));
            given().accept("application/json").when().get("/q/health/live")
                .then().statusCode(200).body("status", equalTo("UP"));
            given().accept("application/json").when().get("/q/health/started")
                .then().statusCode(200);
        } finally {
            ToggledReadiness.DEPENDENCY_UP.set(true);
        }
        given().accept("application/json").when().get("/q/health/ready").then().statusCode(200);
    }
}
