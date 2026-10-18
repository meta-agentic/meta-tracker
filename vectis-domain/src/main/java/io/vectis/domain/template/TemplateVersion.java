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

    /** The parent this version extends, as {@code key@version}, or null for a root. */
    public String parent() {
        Object e = document.get("extends");
        return e == null ? null : e instanceof Map<?, ?> m ? ref(m.get("template"), m.get("version")) : String.valueOf(e);
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
