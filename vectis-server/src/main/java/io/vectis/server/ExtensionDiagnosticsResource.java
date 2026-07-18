package io.vectis.server;

import io.vectis.core.extension.ExtensionRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Read-only operator diagnostics: which extension implementations the running
 * image discovered and which is active for each seam. Lets an operator confirm
 * at a glance whether a deployment is running community or commercial plugins.
 */
@Path("/api/extensions")
public class ExtensionDiagnosticsResource {

    private final ExtensionRegistry registry;

    @Inject
    public ExtensionDiagnosticsResource(ExtensionRegistry registry) {
        this.registry = registry;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ExtensionRegistry.ExtensionReport extensions() {
        return registry.report();
    }
}
