// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vectis.domain.template.Violation;
import java.util.List;

/**
 * The one error body of {@code /api/v1}: an RFC 9457 {@code application/problem+json} document
 * whose {@code violations} member carries the resolver's typed {@code {field, level, message}}.
 *
 * @param type       always {@code about:blank}: the status code says all there is to say
 * @param violations present only on a 422
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(String type, String title, int status, String detail, List<Violation> violations) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public static ProblemDetail of(int status, String detail, List<Violation> violations) {
        return new ProblemDetail("about:blank", title(status), status, detail, violations);
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 412 -> "Precondition Failed";
            case 422 -> "Unprocessable Content";
            default -> "Error";
        };
    }
}
