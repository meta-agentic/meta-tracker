package io.vectis.spike.session;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

public class RequestGuards {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    @ConfigProperty(name = "vectis.bearer.allowed-clients")
    List<String> allowedClients;

    @Inject
    SecurityIdentity identity;

    @Inject
    JsonWebToken accessToken;

    /**
     * CSRF (D6): a state-changing request authenticated by the ambient session cookie must carry
     * {@code X-Requested-With: JavaScript}. A cross-site page cannot add a custom header without a
     * CORS preflight, and Vectis answers no preflight. The same header tells Quarkus to answer an
     * expired session with a status instead of a redirect. Bearer calls carry no ambient
     * credential and are exempt.
     */
    @ServerRequestFilter
    public Response csrf(ContainerRequestContext request) {
        if (SAFE.contains(request.getMethod()) || request.getHeaderString("Authorization") != null) {
            return null;
        }
        return "JavaScript".equals(request.getHeaderString("X-Requested-With"))
                ? null
                : Response.status(403).entity("{\"error\":\"csrf\"}").build();
    }

    /**
     * The provider sets aud = issuer on every access token, so the audience check alone admits a
     * token minted for any client of that provider. Only tokens issued to an allow-listed client
     * reach the API (finding F3).
     */
    @ServerRequestFilter
    public Response bearerClient(ContainerRequestContext request) {
        if (!"bearer".equals(identity.getAttribute("tenant-id"))) {
            return null;
        }
        Object clientId = accessToken.getClaim("client_id");
        return clientId != null && allowedClients.contains(clientId.toString())
                ? null
                : Response.status(401).entity("{\"error\":\"client_not_allowed\"}").build();
    }
}
