package io.vectis.domain.template;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of resolving a template level or a workspace: the effective configuration and
 * every violation found. The document is returned even when violations exist, so a caller can
 * show what the refused change would have produced; it is only valid when {@link #ok()}.
 */
public record Resolution(Map<String, Object> effective, List<Violation> violations) {

    public Resolution {
        Objects.requireNonNull(effective, "effective");
        violations = List.copyOf(violations);
    }

    public boolean ok() {
        return violations.isEmpty();
    }
}
