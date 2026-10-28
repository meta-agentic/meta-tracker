// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.vectis.domain.template.Violation;
import java.util.List;

/**
 * Thrown by an {@code /api/v1} resource to answer with a problem document. One factory per
 * status of the conventions, so a resource says which case it is and not which number.
 */
public class ProblemException extends RuntimeException {

    private final int status;
    private final List<Violation> violations;

    private ProblemException(int status, String detail, List<Violation> violations) {
        super(detail);
        this.status = status;
        this.violations = violations;
    }

    /** 400: the request is malformed. */
    public static ProblemException badRequest(String detail) {
        return new ProblemException(400, detail, null);
    }

    /** 404: the key names nothing. */
    public static ProblemException notFound(String detail) {
        return new ProblemException(404, detail, null);
    }

    /** 409: the request conflicts with the current state. */
    public static ProblemException conflict(String detail) {
        return new ProblemException(409, detail, null);
    }

    /** 412: the {@code If-Match} revision is not the current one. */
    public static ProblemException staleRevision(String detail) {
        return new ProblemException(412, detail, null);
    }

    /** 422: the request is well formed but breaks the rules. */
    public static ProblemException unprocessable(String detail, List<Violation> violations) {
        return new ProblemException(422, detail, List.copyOf(violations));
    }

    public ProblemDetail problem() {
        return ProblemDetail.of(status, getMessage(), violations);
    }

    public int status() {
        return status;
    }
}
