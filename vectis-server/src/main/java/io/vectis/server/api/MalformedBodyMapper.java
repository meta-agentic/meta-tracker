package io.vectis.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * A body that is not JSON, or not the expected shape, is the client's error and gets
 * the same {@link ApiError} shape as any other validation failure. The parser's own
 * message is withheld: it quotes the input back and names internal types.
 *
 * <p>Two entry points because Quarkus REST reports the two cases differently: a parse
 * failure arrives wrapped in a {@link WebApplicationException}, while a well-formed
 * body of the wrong shape arrives as a bare {@link MismatchedInputException}.
 */
public class MalformedBodyMapper {

    @ServerExceptionMapper
    public Response wrapped(WebApplicationException failure) {
        if (failure.getCause() instanceof JsonProcessingException) {
            return malformed();
        }
        return failure.getResponse();
    }

    @ServerExceptionMapper
    public Response mismatched(MismatchedInputException ignored) {
        return malformed();
    }

    private static Response malformed() {
        return ApiError.validation(List.of(new ApiError.Violation("body", "must be a JSON object")));
    }
}
