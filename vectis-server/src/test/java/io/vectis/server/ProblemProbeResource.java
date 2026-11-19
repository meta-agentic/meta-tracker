package io.vectis.server;

import io.vectis.server.api.ProblemException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only: these routes throw the 409 and 412 of the {@code /api/v1} conventions directly, apart
 * from any endpoint that produces them (the item writes of VEC-16 do), and
 * {@link ConfigurationApiTest} pins that the one mapper shapes them like every other problem.
 */
@Path("/api/v1/probe/problem")
public class ProblemProbeResource {

    @GET
    @Path("conflict")
    @Produces(MediaType.APPLICATION_JSON)
    public String conflict() {
        throw ProblemException.conflict("the sprint is already active");
    }

    @GET
    @Path("stale")
    @Produces(MediaType.APPLICATION_JSON)
    public String stale() {
        throw ProblemException.staleRevision("the configuration is at revision 8, not 7");
    }
}
