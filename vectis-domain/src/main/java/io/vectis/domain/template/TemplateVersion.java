package io.vectis.domain.template;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A template version as authored: a root (a complete document) or derived (it names the exact
 * parent version it extends and carries a delta over that parent's resolved document).
 *
 * @param owner {@link TemplateResolver#SYSTEM} for a built-in, the tenant otherwise
 */
public record TemplateVersion(String key, long version, String owner, Map<String, Object> document) {

    public TemplateVersion {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(document, "document");
    }

    /** The version a document declares, by its {@code template} and {@code version} members. */
    public static TemplateVersion of(String owner, Map<String, Object> document) {
        return new TemplateVersion(document.get("template") instanceof String k ? k : "",
                document.get("version") instanceof Long n ? n : 0, owner, document);
    }

    public static String ref(Object key, Object version) {
        return key + "@" + version;
    }

    public String ref() {
        return ref(key, version);
    }

    /** The parent this version extends, as {@code key@version}; null for a root or a malformed {@code extends}. */
    public String parent() {
        return document.containsKey("extends") && extendsWellFormed()
                ? ref(((Map<?, ?>) document.get("extends")).get("template"), ((Map<?, ?>) document.get("extends")).get("version"))
                : null;
    }

    /**
     * Whether {@code extends}, when present, has the schema's shape: exactly a template key and a
     * version from 1. A root has no {@code extends} and is well formed.
     */
    public boolean extendsWellFormed() {
        Object e = document.get("extends");
        return !document.containsKey("extends") || e instanceof Map<?, ?> m && m.size() == 2
                && m.get("template") instanceof String t && LevelResolver.KEY.matcher(t).matches()
                && m.get("version") instanceof Long n && n >= 1;
    }

    /** The published template versions a chain is looked up in. */
    public interface Catalog {

        Optional<TemplateVersion> find(String ref);

        /** Every published version of a key; empty for a key not yet published. */
        List<TemplateVersion> versions(String key);

        default long latest(String key) {
            return versions(key).stream().mapToLong(TemplateVersion::version).max().orElse(0);
        }

        /** A catalog of exactly these versions. */
        static Catalog of(List<TemplateVersion> versions) {
            return new Catalog() {
                @Override
                public Optional<TemplateVersion> find(String ref) {
                    return versions.stream().filter(x -> x.ref().equals(ref)).findFirst();
                }

                @Override
                public List<TemplateVersion> versions(String key) {
                    return versions.stream().filter(x -> x.key().equals(key)).toList();
                }
            };
        }
    }
}
