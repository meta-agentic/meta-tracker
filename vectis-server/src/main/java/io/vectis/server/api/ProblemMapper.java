// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.command.CommandException;
import io.vectis.command.CommandRejectedException;
import io.vectis.command.ReadOnlyWorkspaceException;
import io.vectis.command.StaleVersionException;
import io.vectis.command.UnknownKeyException;
import io.vectis.command.VersionRequiredException;
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

    /** A command the command layer refused, by what was wrong. */
    @ServerExceptionMapper
    public Response refused(CommandException e) {
        return respond(switch (e) {
            case UnknownKeyException unknown -> ProblemDetail.of(404, unknown.getMessage(), null);
            case ReadOnlyWorkspaceException readOnly -> ProblemDetail.of(409, readOnly.getMessage(), null);
            case VersionRequiredException _ -> ProblemDetail.of(400, "a write to an existing item takes its version as"
                    + " If-Match, in quotes, as the item's ETag gives it, such as \"3\"", null);
            case StaleVersionException stale -> ProblemDetail.of(412, stale.getMessage(), null);
            case CommandRejectedException rejected -> ProblemDetail.of(422, rejected.getMessage(), rejected.violations());
        });
    }

    private static Response respond(ProblemDetail problem) {
        return Response.status(problem.status()).type(ProblemDetail.MEDIA_TYPE).entity(problem).build();
    }
}
