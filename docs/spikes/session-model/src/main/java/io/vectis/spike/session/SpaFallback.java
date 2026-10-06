package io.vectis.spike.session;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;

/** Client-side routes reload into the SPA shell (the full version is VEC-78). */
@Path("/board")
public class SpaFallback {

    @GET
    @Path("{rest: .*}")
    @Produces(MediaType.TEXT_HTML)
    public Response shell() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/META-INF/resources/index.html")) {
            return Response.ok(in.readAllBytes()).header("Cache-Control", "no-store").build();
        }
    }
}
