// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.vectis.domain.template.Violation;
import java.util.List;

/**
 * The one error body of {@code /api/v1}: an RFC 9457 {@code application/problem+json} document
 * whose {@code violations} member carries the typed {@code {field, level, message, code?}}
 * (ADR-VEC-05; {@code code} by its amendment for VEC-70).
 *
 * @param type       always {@code about:blank}: the status code says all there is to say
 * @param violations present on a 422, and on a 409 that names the field it is about
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(String type, String title, int status, String detail, List<Item> violations) {

    public static final String MEDIA_TYPE = "application/problem+json";

    public static ProblemDetail of(int status, String detail, List<Violation> violations) {
        return new ProblemDetail("about:blank", title(status), status, detail,
                violations == null ? null : violations.stream().map(Item::of).toList());
    }

    /**
     * One violation as the wire carries it: {@code field} is the path the request or document
     * member has, {@code message} the English fallback, {@code code} what a client translates, left
     * out when there is none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String field, String level, String message, String code) {

        static Item of(Violation v) {
            return new Item(v.field(), v.level(), v.message(), v.code());
        }
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
