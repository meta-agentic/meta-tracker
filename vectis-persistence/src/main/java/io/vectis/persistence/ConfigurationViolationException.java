// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.template.Violation;
import java.util.List;

/**
 * Thrown when a workspace configuration is refused: its delta does not resolve to a valid
 * workflow over its template version. Carries every violation the resolver found, so a caller
 * can report them all at once. Thrown before anything is written.
 */
public class ConfigurationViolationException extends RuntimeException {

    private final List<Violation> violations;

    public ConfigurationViolationException(List<Violation> violations) {
        super("the configuration is refused: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
