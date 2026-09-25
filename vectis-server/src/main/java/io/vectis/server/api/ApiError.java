package io.vectis.server.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * The one error body every product endpoint returns.
 *
 * <p>{@code error} is a stable machine code ({@code validation}, {@code conflict},
 * {@code not_found}); {@code message} is for people; {@code violations} names each
 * offending request field so a form can mark the field rather than show a banner.
 */
public record ApiError(String error, String message, List<Violation> violations) {

    public record Violation(String field, String message) {
    }

    public ApiError {
        violations = List.copyOf(violations);
    }

    static Response validation(List<Violation> violations) {
        return respond(Response.Status.BAD_REQUEST,
                new ApiError("validation", "the request is invalid", violations));
    }

    static Response conflict(String field, String message) {
        return respond(Response.Status.CONFLICT,
                new ApiError("conflict", message, List.of(new Violation(field, message))));
    }

    static Response notFound(String message) {
        return respond(Response.Status.NOT_FOUND, new ApiError("not_found", message, List.of()));
    }

    private static Response respond(Response.Status status, ApiError body) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build();
    }
}
