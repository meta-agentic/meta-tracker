package io.vectis.spike.session;

import io.quarkus.oidc.OidcSession;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/auth")
public class AuthResource {

    @ConfigProperty(name = "vectis.issuer")
    String issuer;

    @Inject
    OidcSession session;

    /**
     * The one protected entry point the sign-in page navigates to once the provider session
     * exists. Reaching it starts the code flow; after the callback Quarkus restores this path
     * (with its query) and the browser is sent on to {@code next}.
     */
    @GET
    @Path("/start")
    @Authenticated
    public Response start(@QueryParam("next") String next) {
        return Response.seeOther(URI.create(safeLocalPath(next))).header("Cache-Control", "no-store").build();
    }

    /** Public: the sign-in page needs to know where the provider lives. */
    @GET
    @Path("/signin-config")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> signinConfig() {
        return Map.of("issuer", issuer);
    }

    /** Ends the Vectis session (local logout). The page then ends the provider session. */
    @POST
    @Path("/logout")
    @Authenticated
    public Uni<Response> logout() {
        return session.logout().map(ignored -> Response.noContent().build());
    }

    /** Only same-origin absolute paths: no scheme, no host, no protocol-relative or backslash tricks. */
    static String safeLocalPath(String next) {
        if (next == null || next.isEmpty() || next.charAt(0) != '/'
                || next.startsWith("//") || next.startsWith("/\\") || next.contains("\r") || next.contains("\n")) {
            return "/";
        }
        return next;
    }
}
