package io.vectis.server;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VEC-78: the SPA and the API on one origin. The shell and the hashed asset are
 * fixtures under {@code src/test/resources/META-INF/resources}, standing in for
 * the {@code web/dist} the image build copies to the same place.
 */
@QuarkusTest
class SpaOneOriginTest {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; "
            + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    static final String SHELL_MARKER = "<div id=\"root\"></div>";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    @Test
    void rootServesTheShellUncached() {
        assertShell(given().accept("text/html").when().get("/").then());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/board/VEC", "/board/VEC/items/VEC-78", "/backlog", "/apiary", "/quality"})
    void clientRouteReloadsIntoTheShell(String path) {
        assertShell(given().accept("text/html").when().get(path).then());
    }

    @Test
    void headOnAClientRouteAnswersLikeTheShell() {
        var response = given().when().head("/board/VEC")
            .then()
                .statusCode(200)
                .header("Content-Security-Policy", CSP);
        assertOnlyCacheControl(response, "no-store");
    }

    @Test
    void hashedAssetIsImmutable() {
        var response = given().when().get("/assets/index-0a1b2c3d.js")
            .then()
                .statusCode(200)
                .header("Content-Security-Policy", CSP)
                .header("X-Content-Type-Options", "nosniff");
        assertOnlyCacheControl(response, IMMUTABLE);
    }

    @Test
    void unhashedTopLevelFileRevalidates() {
        var response = given().when().get("/theme-boot.js")
            .then()
                .statusCode(200)
                .header("Content-Security-Policy", CSP);
        assertOnlyCacheControl(response, "no-cache");
    }

    @Test
    void apiV1AnswersOnTheSameOrigin() {
        given().accept("application/json").when().get("/api/v1/probe")
            .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("api", equalTo("v1"))
                .header("Content-Security-Policy", CSP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/unknown", "/api/v1/unknown", "/q", "/q/unknown",
            "/assets/missing-0a1b2c3d.js", "/board/VEC/attachment.pdf"})
    void unknownApiAndFilePathsAre404NotTheShell(String path) {
        given().accept("text/html").when().get(path)
            .then()
                .statusCode(404)
                .body(not(containsString(SHELL_MARKER)));
    }

    @Test
    void aWriteToAClientRouteIsNotANavigation() {
        given().contentType("application/json").body("{}").when().post("/board/VEC")
            .then()
                .statusCode(not(equalTo(200)))
                .body(not(containsString(SHELL_MARKER)));
    }

    private static void assertShell(ValidatableResponse response) {
        response
            .statusCode(200)
            .contentType(startsWith("text/html"))
            .body(containsString(SHELL_MARKER))
            .header("Content-Security-Policy", CSP)
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer");
        assertOnlyCacheControl(response, "no-store");
    }

    /** One value, not ours beside the static handler's own: a second one would make the policy ambiguous. */
    private static void assertOnlyCacheControl(ValidatableResponse response, String expected) {
        assertEquals(List.of(expected), response.extract().headers().getValues("Cache-Control"));
    }
}
