// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A template version named by its key and version, written {@code key@version}
 * ({@code scrum@1}). The key alone does not say whose template it is: a tenant resolves it among
 * the built-ins and its own templates, which never share a key.
 */
public record TemplateRef(String key, int version) {

    /** The template key pattern of the template schema and of {@code workflow_template.key}. */
    public static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9-]{0,31}");

    public TemplateRef {
        Objects.requireNonNull(key, "key");
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("template key must match " + KEY.pattern() + ": " + key);
        }
        if (version < 1) {
            throw new IllegalArgumentException("template version starts at 1: " + version);
        }
    }

    /** Parses {@code key@version}; anything else is refused. */
    public static TemplateRef parse(String ref) {
        Objects.requireNonNull(ref, "ref");
        int at = ref.lastIndexOf('@');
        if (at < 0 || at == ref.length() - 1) {
            throw new IllegalArgumentException("a template reference is key@version: " + ref);
        }
        String version = ref.substring(at + 1);
        if (!version.chars().allMatch(Character::isDigit) || version.length() > 9) {
            throw new IllegalArgumentException("a template reference is key@version: " + ref);
        }
        return new TemplateRef(ref.substring(0, at), Integer.parseInt(version));
    }

    @Override
    public String toString() {
        return key + "@" + version;
    }
}
