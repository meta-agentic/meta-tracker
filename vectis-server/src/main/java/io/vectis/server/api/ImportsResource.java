// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.smallrye.mutiny.Uni;
import io.vectis.connector.vault.VaultBacklogSource;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Runs an import (VEC-54). {@code POST /api/v1/imports/{sourceId}} reads the source the server is
 * configured with, never one a request names, and writes what it finds into the workspace the
 * source's keys belong to. Running it again with nothing changed in the source changes nothing,
 * so it is a POST that is safe to repeat. The only source is {@code vault}.
 */
@Path("/api/v1/imports")
@Produces(MediaType.APPLICATION_JSON)
public class ImportsResource {

    private final VaultImport vault;

    public ImportsResource(VaultImport vault) {
        this.vault = vault;
    }

    @POST
    @Path("/{sourceId}")
    public Uni<VaultImport.Report> run(@PathParam("sourceId") String sourceId) {
        if (!VaultBacklogSource.ID.equals(sourceId)) {
            throw ProblemException.notFound("import source " + sourceId + " does not exist");
        }
        if (!vault.configured()) {
            throw ProblemException.notFound("import source " + sourceId + " is not configured on this server");
        }
        return vault.run();
    }
}
