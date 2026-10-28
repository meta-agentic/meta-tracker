// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.persistence.ConfigurationViolationException;
import io.vectis.persistence.TemplateNotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * The single place that turns what the persistence layer and the resources throw into the
 * {@link ProblemDetail} of the {@code /api/v1} conventions.
 */
public class ProblemMapper {

    @ServerExceptionMapper
    public Response problem(ProblemException e) {
        return respond(e.problem());
    }

    /** A refused configuration: the resolver's violations, verbatim, on a 422. */
    @ServerExceptionMapper
    public Response violations(ConfigurationViolationException e) {
        return respond(ProblemDetail.of(422, "the configuration does not resolve to a valid workflow", e.violations()));
    }

    @ServerExceptionMapper
    public Response notFound(TemplateNotFoundException e) {
        return respond(ProblemDetail.of(404, e.getMessage(), null));
    }

    private static Response respond(ProblemDetail problem) {
        return Response.status(problem.status()).type(ProblemDetail.MEDIA_TYPE).entity(problem).build();
    }
}
