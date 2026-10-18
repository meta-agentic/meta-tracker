package io.vectis.domain.template;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The outcome of resolving a template level, a template chain or a workspace: the effective
 * configuration, which level set each of its values, and every violation found. The document is
 * returned even when violations exist, so a caller can show what the refused change would have
 * produced; it is only valid when {@link #ok()}.
 *
 * @param effective  the effective configuration document
 * @param provenance per field path, the level that last set it ({@code "states.in-review.name"} →
 *                   {@code "acme@1"}); empty where the resolution did not compute it
 * @param violations every reason the result is refused; empty when it is valid
 */
public record Resolution(Map<String, Object> effective, Map<String, String> provenance, List<Violation> violations) {

    public Resolution {
        Objects.requireNonNull(effective, "effective");
        provenance = Collections.unmodifiableSortedMap(new TreeMap<>(provenance));
        violations = List.copyOf(violations);
    }

    Resolution(Map<String, Object> effective, List<Violation> violations) {
        this(effective, Map.of(), violations);
    }

    public boolean ok() {
        return violations.isEmpty();
    }
}
