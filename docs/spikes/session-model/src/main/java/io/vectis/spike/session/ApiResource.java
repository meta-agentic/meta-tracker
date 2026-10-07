package io.vectis.spike.session;

import io.quarkus.oidc.IdToken;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/api/v1")
@Authenticated
public class ApiResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    JsonWebToken accessToken;

    /** Who the caller is, by which path, and which tenant claims the tokens carry. */
    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> me() {
        boolean bearer = "bearer".equals(identity.getAttribute("tenant-id"));
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("subject", identity.getPrincipal().getName());
        me.put("path", bearer ? "bearer" : "session");
        if (!bearer) {
            me.put("idToken.realm_tenant", idToken.getClaim("realm_tenant"));
            me.put("idToken.email", idToken.getClaim("email"));
        }
        me.put("accessToken.realm_tenant", accessToken.getClaim("realm_tenant"));
        me.put("accessToken.client_id", accessToken.getClaim("client_id"));
        me.put("accessToken.aud", accessToken.getAudience());
        return me;
    }

    /** A state-changing call, to exercise the CSRF rule. */
    @POST
    @Path("/items")
    public Response create() {
        return Response.status(201).build();
    }

    /** The event stream rides the session cookie: EventSource cannot send an Authorization header. */
    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> stream() {
        return Multi.createFrom().ticks().every(Duration.ofMillis(200)).map(n -> "tick-" + n).select().first(500);
    }
}
