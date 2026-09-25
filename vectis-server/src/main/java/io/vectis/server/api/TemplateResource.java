package io.vectis.server.api;

import io.vectis.domain.ProjectTemplate;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.stream.Stream;

/** The provisioning templates a "New Project" form offers. */
@Path("/api/v1/templates")
@Produces(MediaType.APPLICATION_JSON)
public class TemplateResource {

    @GET
    public List<WireViews.TemplateView> list() {
        return Stream.of(ProjectTemplate.values()).map(WireViews.TemplateView::of).toList();
    }
}
