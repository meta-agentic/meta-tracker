// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.smallrye.mutiny.Uni;
import io.vectis.domain.template.Resolution;
import io.vectis.persistence.PublishedTemplate;
import io.vectis.persistence.TemplateNotFoundException;
import io.vectis.persistence.TemplateRepository;
import io.vectis.persistence.WorkspaceConfiguration;
import io.vectis.persistence.WorkspaceRepository;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/** A workspace's effective configuration (VEC-69): its template, its delta and what they resolve to. */
@Path("/api/v1/workspaces/{key}/configuration")
public class WorkspaceConfigurationResource {

    private final WorkspaceRepository workspaces;
    private final TemplateRepository templates;
    private final TemplateChains chains;

    public WorkspaceConfigurationResource(WorkspaceRepository workspaces, TemplateRepository templates,
                                          TemplateChains chains) {
        this.workspaces = workspaces;
        this.templates = templates;
        this.chains = chains;
    }

    /**
     * @param include {@code provenance} adds, per field path, the level that set it
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Response> get(@PathParam("key") String key, @QueryParam("include") String include) {
        boolean provenance = includesProvenance(include);
        return workspaces.findConfiguration(key)
                .map(found -> found.orElseThrow(() -> ProblemException.notFound("workspace " + key + " does not exist")))
                .chain(config -> templates.findVersion(config.templateTenantId(), config.template())
                        .map(version -> version.orElseThrow(() -> new TemplateNotFoundException(config.template())))
                        .chain(chains::chain)
                        .map(chain -> view(config, chain, provenance)))
                .map(view -> Response.ok(view).tag(new EntityTag(Long.toString(view.revision()))).build());
    }

    private View view(WorkspaceConfiguration config, List<PublishedTemplate> chain, boolean provenance) {
        Resolution resolution = chains.resolve(chain, config.delta(), provenance);
        return new View(
                new Template(config.template().key(), config.template().version(), config.latestVersion(),
                        config.follow(), chain.stream().map(TemplateViews.ChainLink::of).toList()),
                config.revision(), config.delta(), resolution.effective(),
                provenance ? resolution.provenance() : null);
    }

    private static boolean includesProvenance(String include) {
        if (include == null) {
            return false;
        }
        boolean provenance = false;
        for (String part : include.split(",", -1)) {
            if (!part.equals("provenance")) {
                throw ProblemException.badRequest("include accepts: provenance; got '" + part + "'");
            }
            provenance = true;
        }
        return provenance;
    }

    record Template(String key, int version, int latestVersion, String follow, List<TemplateViews.ChainLink> chain) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record View(Template template, long revision, Map<String, Object> delta, Map<String, Object> effective,
                Map<String, String> provenance) {
    }
}
