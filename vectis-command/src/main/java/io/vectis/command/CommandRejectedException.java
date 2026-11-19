// SPDX-License-Identifier: Apache-2.0
package io.vectis.command;

import io.vectis.domain.template.Violation;
import java.util.List;

/** The command is well formed and breaks the rules; {@link #violations()} says which, field by field. */
public final class CommandRejectedException extends CommandException {

    private final List<Violation> violations;

    CommandRejectedException(List<Violation> violations) {
        super("the item breaks the workspace's rules");
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }
}
