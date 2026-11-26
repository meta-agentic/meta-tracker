package io.vectis.domain.template;

import java.util.Objects;

/**
 * Why a template document, a level of a template chain, a workspace delta or an item write is
 * refused.
 *
 * @param field   the path the violation is about: {@code "states.qa.wipLimit"}, {@code "stateOrder"},
 *                {@code "extends"}, {@code "locks"}; for an item write, the request member, such as
 *                {@code "title"} or {@code "fields.storyPoints"}
 * @param level   the level that carries it, as {@code key@version} or {@code "workspace"}; null
 *                while a single-level resolution has not been placed in a chain
 * @param message what is wrong, in words a template author can act on; the English fallback a
 *                client shows when it does not know the {@code code}
 * @param code    what kind of rule was broken, from a closed set a client translates
 *                ({@code required}, {@code requiredToEnter}, ...); null where no client needs one
 */
public record Violation(String field, String level, String message, String code) {

    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }

    /** A violation without a code. */
    public Violation(String field, String level, String message) {
        this(field, level, message, null);
    }

    static Violation of(String field, String message) {
        return new Violation(field, null, message);
    }

    /** The same violation, attributed to {@code level} unless it already names one. */
    Violation at(String level) {
        return this.level != null ? this : new Violation(field, level, message, code);
    }

    @Override
    public String toString() {
        return (level == null ? "" : level + ": ") + field + ": " + message;
    }
}
