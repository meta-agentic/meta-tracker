package io.vectis.server;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Test-only stand-in for the first {@code /api/v1} resource, which no story has
 * shipped yet. It lets {@link SpaOneOriginTest} show the API and the SPA answering
 * on the same origin without inventing a production endpoint.
 */
@Path("/api/v1/probe")
public class ApiV1ProbeResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> probe() {
        return Map.of("api", "v1");
    }
}
