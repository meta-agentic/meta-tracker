// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import io.vectis.domain.TimeOrderedId;
import io.vectis.domain.template.Resolution;
import io.vectis.domain.template.TemplateJson;
import io.vectis.domain.template.TemplateResolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Inserts the built-in template versions, which ship as files in {@code vectis-domain}'s
 * {@code templates/} resources, into {@code workflow_template_version}.
 *
 * <p>Built-in versions exist only as reviewed files and are inserted only by migrations
 * (ADR-VEC-03 §6), so this runs inside a Flyway migration on its JDBC connection, never on a
 * request path. It is idempotent: a version already stored is skipped, provided the file still
 * says what was stored and still resolves to what was stored. A published version is immutable,
 * so a file, or a resolution, that changed under an existing version number fails the migration
 * instead of being silently ignored.
 */
public final class BuiltInTemplates {

    /**
     * Every published built-in version, each after the version it extends. A new version is a new
     * file appended here (and to the test's digest list); an existing file is never edited.
     */
    public static final List<String> PUBLISHED = List.of("base.v1", "scrum.v1", "kanban.v1", "hybrid.v1", "stage-gate.v1");

    /**
     * The one exception to immutability: versions the PO revised in place before any customer
     * depended on them (VEC-70: {@code scrum@1} gained the form vocabulary, and {@code hybrid@1}
     * resolves to more because it extends it). Only {@link #revise} may overwrite them, and only
     * these.
     */
    static final List<String> REVISED_IN_PLACE = List.of("scrum@1", "hybrid@1");

    static final String AUTHORED = "{\"kind\": \"authored\"}";

    private BuiltInTemplates() {
    }

    /** The text of a shipped file, {@code templates/<name>.json} on the classpath. */
    public static String file(String name) {
        String path = "templates/" + name + ".json";
        try (InputStream in = BuiltInTemplates.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("built-in template file missing from the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }

    /** Inserts every {@link #PUBLISHED} version not yet stored; returns how many were inserted. */
    public static int seed(Connection c) throws SQLException {
        return seed(c, BuiltInTemplates::file);
    }

    /**
     * Overwrites the stored document and resolution of each {@link #REVISED_IN_PLACE} version that
     * differs from its file, then inserts what is missing as {@link #seed(Connection)} does. Run once,
     * by the migration that ships the revision, on a database that published the old text.
     */
    public static int revise(Connection c) throws SQLException {
        return seed(c, BuiltInTemplates::file, REVISED_IN_PLACE);
    }

    /** As {@link #seed(Connection)}, reading each file's text through {@code files}. */
    static int seed(Connection c, Function<String, String> files) throws SQLException {
        return seed(c, files, List.of());
    }

    /** As {@link #seed(Connection)}, but a stored version named in {@code overwritable} that differs is overwritten. */
    private static int seed(Connection c, Function<String, String> files, List<String> overwritable)
            throws SQLException {
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byRef = new LinkedHashMap<>();
        for (String name : PUBLISHED) {
            String text = files.apply(name);
            Map<String, Object> doc = TemplateJson.parseObject(text);
            String ref = doc.get("template") + "@" + doc.get("version");
            if (!name.equals(doc.get("template") + ".v" + doc.get("version"))) {
                throw new IllegalStateException(name + ".json declares " + ref);
            }
            texts.put(ref, text);
            byRef.put(ref, doc);
        }
        int inserted = 0;
        for (Map.Entry<String, Map<String, Object>> e : byRef.entrySet()) {
            String ref = e.getKey();
            Map<String, Object> doc = e.getValue();
            String key = (String) doc.get("template");
            int version = Math.toIntExact((Long) doc.get("version"));
            UUID templateId = templateId(c, key);
            Resolution r = TemplateResolver.resolve(chain(doc, byRef), null);
            if (!r.ok()) {
                throw new IllegalStateException("built-in " + ref + " does not resolve: " + r.violations());
            }
            String resolved = TemplateJson.compact(r.effective());
            Stored stored = stored(c, templateId, version, texts.get(ref), resolved);
            switch (stored) {
                case SAME -> {
                }
                case ABSENT -> {
                    insertVersion(c, templateId, version, doc, texts.get(ref), resolved);
                    inserted++;
                }
                case DOCUMENT_CHANGED, RESOLUTION_CHANGED -> {
                    if (!overwritable.contains(ref)) {
                        throw new IllegalStateException("built-in " + ref + " " + stored.refusal);
                    }
                    overwrite(c, templateId, version, texts.get(ref), resolved);
                    inserted++;
                }
            }
        }
        return inserted;
    }

    /** The chain from the root down to {@code leaf}, root first, looked up among the shipped files. */
    private static List<Map<String, Object>> chain(Map<String, Object> leaf, Map<String, Map<String, Object>> byRef) {
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Map<String, Object> x = leaf; x != null; ) {
            chain.addFirst(x);
            if (!(x.get("extends") instanceof Map<?, ?> parent)) {
                break;
            }
            String ref = parent.get("template") + "@" + parent.get("version");
            x = byRef.get(ref);
            if (x == null) {
                throw new IllegalStateException(chain.getFirst().get("template") + " extends " + ref
                        + ", which is not a published built-in listed before it");
            }
        }
        return chain;
    }

    private static UUID templateId(Connection c, String key) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                insert into workflow_template (id, tenant_id, key) values (?, null, ?)
                on conflict on constraint workflow_template_owner_key do nothing
                """)) {
            st.setObject(1, TimeOrderedId.next());
            st.setString(2, key);
            st.executeUpdate();
        }
        try (PreparedStatement st = c.prepareStatement(
                "select id from workflow_template where tenant_id is null and key = ?")) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    /** How a stored version compares with its file; a change is refused, in these words, unless it may be overwritten. */
    private enum Stored {
        ABSENT(null),
        SAME(null),
        DOCUMENT_CHANGED("is published and its file has changed;"
                + " published versions are immutable, so the change is a new version file"),
        RESOLUTION_CHANGED("is published with a resolution that differs"
                + " from what its files resolve to now; a published resolution never changes silently");

        private final String refusal;

        Stored(String refusal) {
            this.refusal = refusal;
        }
    }

    /** How the stored version, if any, compares as JSON with the file's {@code document} and its {@code resolved}. */
    private static Stored stored(Connection c, UUID templateId, int version, String document, String resolved)
            throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                select document = ?::jsonb, resolved = ?::jsonb
                  from workflow_template_version where template_id = ? and version = ?
                """)) {
            st.setString(1, document);
            st.setString(2, resolved);
            st.setObject(3, templateId);
            st.setInt(4, version);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Stored.ABSENT;
                }
                return !rs.getBoolean(1) ? Stored.DOCUMENT_CHANGED
                        : !rs.getBoolean(2) ? Stored.RESOLUTION_CHANGED : Stored.SAME;
            }
        }
    }

    private static void overwrite(Connection c, UUID templateId, int version, String document, String resolved)
            throws SQLException {
        // The immutability trigger guards every other write; it steps aside for this one statement.
        try (var toggle = c.createStatement()) {
            toggle.execute("alter table workflow_template_version disable trigger workflow_template_version_immutable");
        }
        try (PreparedStatement st = c.prepareStatement("""
                update workflow_template_version set document = ?::jsonb, resolved = ?::jsonb, resolver_version = ?
                 where template_id = ? and version = ?
                """)) {
            st.setString(1, document);
            st.setString(2, resolved);
            st.setInt(3, TemplateResolver.RESOLVER_VERSION);
            st.setObject(4, templateId);
            st.setInt(5, version);
            st.executeUpdate();
        } finally {
            try (var toggle = c.createStatement()) {
                toggle.execute("alter table workflow_template_version enable trigger workflow_template_version_immutable");
            }
        }
    }

    private static void insertVersion(Connection c, UUID templateId, int version, Map<String, Object> doc,
                                      String document, String resolved) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                insert into workflow_template_version
                       (template_id, version, parent_id, parent_version, document, resolved, resolver_version, cause)
                select ?, ?, p.id, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb
                  from (select 1) one
                  left join workflow_template p on p.tenant_id is null and p.key = ?
                """)) {
            Map<?, ?> parent = doc.get("extends") instanceof Map<?, ?> m ? m : null;
            st.setObject(1, templateId);
            st.setInt(2, version);
            if (parent == null) {
                st.setNull(3, Types.INTEGER);
            } else {
                st.setInt(3, Math.toIntExact((Long) parent.get("version")));
            }
            st.setString(4, document);
            st.setString(5, resolved);
            st.setInt(6, TemplateResolver.RESOLVER_VERSION);
            st.setString(7, AUTHORED);
            st.setString(8, parent == null ? null : (String) parent.get("template"));
            st.executeUpdate();
        }
    }
}
