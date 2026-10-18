package io.vectis.domain.template;

import java.util.Objects;

/**
 * Why a template document, a level of a template chain or a workspace delta is refused.
 *
 * @param field   the path the violation is about: {@code "states.qa.wipLimit"}, {@code "stateOrder"},
 *                {@code "extends"}, {@code "locks"}
 * @param level   the level that carries it, as {@code key@version} or {@code "workspace"}; null
 *                while a single-level resolution has not been placed in a chain
 * @param message what is wrong, in words a template author can act on
 */
public record Violation(String field, String level, String message) {

    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }

    static Violation of(String field, String message) {
        return new Violation(field, null, message);
    }

    /** The same violation, attributed to {@code level} unless it already names one. */
    Violation at(String level) {
        return this.level != null ? this : new Violation(field, level, message);
    }

    @Override
    public String toString() {
        return (level == null ? "" : level + ": ") + field + ": " + message;
    }
}
