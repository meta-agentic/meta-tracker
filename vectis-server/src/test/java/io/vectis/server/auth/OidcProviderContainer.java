package io.vectis.server.auth;

import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The OIDC provider for integration tests (VEC-79): a real authorization server in a
 * container, provisioned at boot with one realm, one confidential client that must use
 * PKCE (S256), and one password user, from {@code oidc/vectis-realm.json} (Keycloak
 * requires the file to be named after the realm).
 *
 * <p>Keycloak stands in until the provider ADR-VEC-02 names publishes a container image;
 * tests depend only on {@link #issuer()} and the constants below, so swapping the image
 * touches this class and the realm fixture only.
 *
 * <p>Bumping the image: take the newest release tag from quay.io/keycloak/keycloak, pin
 * its manifest digest in {@link #IMAGE} (tag kept as the comment), run
 * {@code OidcProviderPkceTest}, and copy the same digest into {@code .github/workflows/e2e.yml}.
 */
public final class OidcProviderContainer extends GenericContainer<OidcProviderContainer> {

    /** keycloak 26.8.1 */
    static final String IMAGE =
        "quay.io/keycloak/keycloak@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244";

    public static final String REALM = "vectis";
    public static final String CLIENT_ID = "vectis-web";
    public static final String CLIENT_SECRET = "test-only-not-a-secret";
    public static final String USERNAME = "alice";
    public static final String PASSWORD = "alice-test-password";

    /** AC 2: a harness slower than this fails the run instead of hiding a regression. */
    static final Duration STARTUP_BUDGET = Duration.ofSeconds(60);

    private static final int PORT = 8080;

    public OidcProviderContainer() {
        super(DockerImageName.parse(IMAGE));
        withCopyFileToContainer(MountableFile.forClasspathResource("oidc/vectis-realm.json"),
            "/opt/keycloak/data/import/vectis-realm.json");
        withCommand("start-dev", "--import-realm");
        withExposedPorts(PORT);
        waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
            .forPort(PORT)
            .forStatusCode(200)
            .withStartupTimeout(STARTUP_BUDGET));
    }

    /** The issuer as the test JVM reaches it; Keycloak derives it from the request host. */
    public String issuer() {
        return "http://" + getHost() + ":" + getMappedPort(PORT) + "/realms/" + REALM;
    }
}
