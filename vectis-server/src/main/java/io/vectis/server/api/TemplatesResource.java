// SPDX-License-Identifier: Apache-2.0
package io.vectis.server.api;

import io.smallrye.mutiny.Uni;
import io.vectis.domain.template.TemplateResolver;
import io.vectis.persistence.TemplateNotFoundException;
import io.vectis.persistence.TemplateRef;
import io.vectis.persistence.TemplateRepository;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The published templates (VEC-69). Tenant-agnostic until VEC-80: every request sees the
 * built-ins, which is all {@link TemplateRepository} shows a null tenant.
 */
@Path("/api/v1/templates")
public class TemplatesResource {

    private static final String SCHEMA_RESOURCE = "/templates/template.schema.json";

    private final TemplateRepository templates;
    private final TemplateChains chains;

    public TemplatesResource(TemplateRepository templates, TemplateChains chains) {
        this.templates = templates;
        this.chains = chains;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<Summary>> list() {
        return templates.findVisible(null).map(all -> all.stream()
                .map(t -> new Summary(t.key(), TemplateViews.owner(t.tenantId()), t.latest().version(),
                        TemplateViews.Ref.of(t.parent()), t.follow()))
                .toList());
    }

    /** The JSON Schema of a template document; its ETag is the digest of the schema served. */
    @GET
    @Path("schema")
    @Produces("application/schema+json")
    public Response schema() {
        return Response.ok(SchemaHolder.TEXT).tag(new EntityTag(SchemaHolder.DIGEST)).build();
    }

    @GET
    @Path("{key}/versions/{v}")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<Version> version(@PathParam("key") String key, @PathParam("v") String v) {
        TemplateRef ref = ref(key, v);
        return templates.findVersion(null, ref)
                .map(found -> found.orElseThrow(() -> new TemplateNotFoundException(ref)))
                .chain(version -> chains.chain(version).map(chain -> {
                    var resolution = chains.resolve(chain, null, true);
                    return new Version(version.document(), resolution.effective(), TemplateViews.Ref.of(version.parent()),
                            resolution.provenance());
                }));
    }

    private static TemplateRef ref(String key, String v) {
        try {
            return new TemplateRef(key, Integer.parseInt(v));
        } catch (IllegalArgumentException e) {
            throw ProblemException.badRequest("a version path is /templates/{key}/versions/{n}, with a key like 'scrum' and n from 1: "
                    + key + "/" + v);
        }
    }

    record Summary(String key, String owner, int latest, TemplateViews.Ref parent, String follow) {
    }

    record Version(Map<String, Object> document, Map<String, Object> resolved, TemplateViews.Ref parent,
                   Map<String, String> provenance) {
    }

    private static final class SchemaHolder {
        static final String TEXT;
        static final String DIGEST;

        static {
            try (InputStream in = TemplateResolver.class.getResourceAsStream(SCHEMA_RESOURCE)) {
                TEXT = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                DIGEST = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(TEXT.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
