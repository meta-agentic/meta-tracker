package io.vectis.server.auth;

import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * {@link OidcProviderContainer} as a Quarkus test resource, for {@code @QuarkusTest}s that
 * sign in against a real provider (VEC-35 onwards):
 * {@code @WithTestResource(OidcProviderResource.class)}. Not {@code @QuarkusTestResource},
 * which starts the provider for every {@code @QuarkusTest} in the module. Pair it with
 * {@code @Testcontainers(disabledWithoutDocker = true)} on the test class so a machine
 * without Docker skips the class instead of failing it.
 */
public class OidcProviderResource implements QuarkusTestResourceLifecycleManager {

    private OidcProviderContainer provider;

    @Override
    public Map<String, String> start() {
        provider = new OidcProviderContainer();
        provider.start();
        return Map.of(
            "quarkus.oidc.auth-server-url", provider.issuer(),
            "quarkus.oidc.client-id", OidcProviderContainer.CLIENT_ID,
            "quarkus.oidc.credentials.secret", OidcProviderContainer.CLIENT_SECRET,
            "vectis.test.oidc.username", OidcProviderContainer.USERNAME,
            "vectis.test.oidc.password", OidcProviderContainer.PASSWORD);
    }

    @Override
    public void stop() {
        if (provider != null) {
            provider.stop();
        }
    }
}
