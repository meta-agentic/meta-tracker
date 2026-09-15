package io.vectis.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A tenant-scoped container for boards and items — the unit a team is given, and the
 * outermost thing an import targets.
 *
 * <p>{@code key} is the short human prefix that item keys are minted from ({@code PROJ}
 * → {@code PROJ-41}). It is held separately from the identifier precisely so it can be
 * changed without rewriting references: item keys store the prefix at mint time, so
 * re-keying a workspace is a display concern, not a data migration.
 */
public record Workspace(UUID id, String key, String name) {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Z][A-Z0-9]{1,9}");

    public Workspace {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "workspace key must be 2-10 chars, upper-case alphanumeric, leading letter: " + key);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("workspace name must not be blank");
        }
    }

    /** A new workspace with a freshly minted time-ordered identifier. */
    public static Workspace create(String key, String name) {
        return new Workspace(TimeOrderedId.next(), key, name);
    }
}
