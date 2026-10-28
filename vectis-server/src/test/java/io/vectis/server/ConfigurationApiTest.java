// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * VEC-69 against PostgreSQL 16: the configuration read API, the template reads and the one
 * problem shape of the {@code /api/v1} conventions.
 */
@QuarkusTest
@TestProfile(ApiTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class ConfigurationApiTest {

    private static final String PROBLEM = "application/problem+json";
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** A Refined state after the Backlog, the import's worked instance (VEC-45). */
    private static final String REFINED = """
            {"states": {"refined": {"name": "Refined", "category": "START_STATE", "after": "backlog"}}}""";

    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Logger chainLog = Logger.getLogger("io.vectis.server.api.TemplateChains");
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(r);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLog() {
        chainLog.addHandler(capture);
    }

    @AfterEach
    void releaseLog() {
        chainLog.removeHandler(capture);
    }

    // --- the conventions (AC1) ---------------------------------------------------------------

    @Test
    void anUnknownWorkspaceIsA404ProblemDocument() {
        problem(given().when().get("/api/v1/workspaces/NOPE/configuration"), 404, "Not Found")
                .body("detail", equalTo("workspace NOPE does not exist"))
                .body("$", not(hasKey("violations")));
    }

    @Test
    void aMalformedIncludeIsA400Problem() {
        String key = workspace("scrum", 1, "{}", 0, "manual");
        problem(given().queryParam("include", "provenance,bogus").when().get("/api/v1/workspaces/" + key + "/configuration"),
                400, "Bad Request")
                .body("detail", equalTo("include accepts: provenance; got 'bogus'"));
    }

    @Test
    void aStoredDeltaThatNoLongerResolvesIsA422WithTheResolversViolations() {
        String key = workspace("scrum", 1, """
                {"states": {"todo": {"wiplimit": 3}}}""", 4, "manual");
        problem(given().when().get("/api/v1/workspaces/" + key + "/configuration"), 422, "Unprocessable Content")
                .body("violations.size()", equalTo(1))
                .body("violations[0].field", equalTo("states.todo.wiplimit"))
                .body("violations[0].level", equalTo("workspace"))
                .body("violations[0].message", not(emptyString()));
    }

    @Test
    void aConflictAndAStaleRevisionAreShapedByTheSameMapper() {
        problem(given().when().get("/api/v1/probe/problem/conflict"), 409, "Conflict")
                .body("detail", equalTo("the sprint is already active"));
        problem(given().when().get("/api/v1/probe/problem/stale"), 412, "Precondition Failed")
                .body("detail", equalTo("the configuration is at revision 8, not 7"));
    }

    // --- GET /workspaces/{key}/configuration (AC2, AC6) -------------------------------------

    @Test
    void theConfigurationNamesTheTemplateItsChainTheDeltaAndWhatTheyResolveTo() {
        String key = workspace("scrum", 1, REFINED, 7, "auto");
        given().when().get("/api/v1/workspaces/" + key + "/configuration")
                .then().statusCode(200)
                .contentType("application/json")
                .header("ETag", equalTo("\"7\""))
                .body("revision", equalTo(7))
                .body("template.key", equalTo("scrum"))
                .body("template.version", equalTo(1))
                .body("template.latestVersion", equalTo(1))
                .body("template.follow", equalTo("auto"))
                .body("template.chain.key", contains("base", "scrum"))
                .body("template.chain.version", contains(1, 1))
                .body("template.chain.owner", contains("system", "system"))
                .body("template.chain.name", contains("Base", "Scrum"))
                .body("delta.states.refined.name", equalTo("Refined"))
                .body("effective.states.refined.name", equalTo("Refined"))
                .body("effective.states.backlog.name", equalTo("Backlog"))
                .body("effective.stateOrder.indexOf('refined')", equalTo(1))
                .body("$", not(hasKey("provenance")));
        assertEquals(List.of(), warnings, "a current resolution is not logged");
    }

    @Test
    void provenanceIsAddedOnlyWhenAskedFor() {
        String key = workspace("scrum", 1, REFINED, 2, "manual");
        given().queryParam("include", "provenance").when().get("/api/v1/workspaces/" + key + "/configuration")
                .then().statusCode(200)
                .header("ETag", equalTo("\"2\""))
                .body("provenance.'states.refined.name'", equalTo("workspace"))
                .body("provenance.'states.backlog.name'", equalTo("scrum@1"))
                .body("provenance.'states.todo.name'", equalTo("base@1"))
                .body("effective.states.refined.name", equalTo("Refined"));
    }

    @Test
    void aStaleResolverVersionIsRecomputedOnReadAndLoggedWithoutFailingTheRequest() throws SQLException {
        String acme = "acme" + SEQ.incrementAndGet();
        tenantTemplate(acme, 1, 0, "{}");
        tenantTemplate(acme, 2, 1, "{}");
        String key = workspace(acme, 1, "{}", 5, "manual");
        given().when().get("/api/v1/workspaces/" + key + "/configuration")
                .then().statusCode(200)
                .header("ETag", equalTo("\"5\""))
                .body("template.key", equalTo(acme))
                .body("template.version", equalTo(1))
                .body("template.latestVersion", equalTo(2))
                .body("template.chain.key", contains("base", acme))
                .body("template.chain.owner[1]", not(equalTo("system")))
                // the stored `resolved` is empty; the effective configuration comes from the chain
                .body("effective.states.todo.name", equalTo("Ready"))
                .body("effective.states.done.name", equalTo("Done"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().getMessage().contains("recomputing"));
    }

    @Test
    void aCurrentResolverVersionIsReadFromTheMaterialisedRow() throws SQLException {
        String acme = "acme" + SEQ.incrementAndGet();
        // resolved differs from what the chain gives, so only the materialised row can answer "Materialised"
        tenantTemplate(acme, 1, 1, null);
        String key = workspace(acme, 1, "{}", 1, "manual");
        given().when().get("/api/v1/workspaces/" + key + "/configuration")
                .then().statusCode(200)
                .body("effective.states.todo.name", equalTo("Materialised"));
        assertEquals(List.of(), warnings);
    }

    // --- GET /templates (AC3) ----------------------------------------------------------------

    @Test
    void theTemplateListHoldsTheVisibleTemplatesWithTheirLatestParentAndFollow() throws SQLException {
        tenantTemplate("acme" + SEQ.incrementAndGet(), 1, 1, "{}");
        given().when().get("/api/v1/templates")
                .then().statusCode(200)
                .body("key", contains("base", "hybrid", "kanban", "scrum"))
                .body("find { it.key == 'base' }.owner", equalTo("system"))
                .body("find { it.key == 'base' }.latest", equalTo(1))
                .body("find { it.key == 'base' }.parent", nullValue())
                .body("find { it.key == 'base' }.follow", equalTo("manual"))
                .body("find { it.key == 'scrum' }.parent.key", equalTo("base"))
                .body("find { it.key == 'scrum' }.parent.version", equalTo(1))
                .body("find { it.key == 'hybrid' }.parent.key", equalTo("scrum"));
    }

    // --- GET /templates/{key}/versions/{v} (AC4) ---------------------------------------------

    @Test
    void aVersionCarriesItsDocumentItsResolutionItsParentAndItsProvenance() {
        given().when().get("/api/v1/templates/scrum/versions/1")
                .then().statusCode(200)
                .body("document.template", equalTo("scrum"))
                .body("document.extends.template", equalTo("base"))
                .body("document.states", not(hasKey("todo")))
                .body("resolved.states.todo.name", equalTo("To Do"))
                .body("resolved.states.backlog.name", equalTo("Backlog"))
                .body("parent.key", equalTo("base"))
                .body("parent.version", equalTo(1))
                .body("provenance.'states.todo.name'", equalTo("base@1"))
                .body("provenance.'states.backlog.name'", equalTo("scrum@1"));
    }

    @Test
    void aRootVersionHasNoParent() {
        given().when().get("/api/v1/templates/base/versions/1")
                .then().statusCode(200)
                .body("parent", nullValue())
                .body("document.template", equalTo("base"))
                .body("provenance.'states.todo.name'", equalTo("base@1"));
    }

    @Test
    void anUnknownTemplateOrVersionIsA404AndAMalformedOneIsA400() {
        problem(given().when().get("/api/v1/templates/nope/versions/1"), 404, "Not Found");
        problem(given().when().get("/api/v1/templates/scrum/versions/9"), 404, "Not Found");
        problem(given().when().get("/api/v1/templates/scrum/versions/abc"), 400, "Bad Request");
        problem(given().when().get("/api/v1/templates/scrum/versions/0"), 400, "Bad Request");
        problem(given().when().get("/api/v1/templates/Bad_Key/versions/1"), 400, "Bad Request");
    }

    // --- GET /templates/schema (AC5) ---------------------------------------------------------

    @Test
    void theSchemaIsServedWithAnETagOfItsDigest() {
        given().when().get("/api/v1/templates/schema")
                .then().statusCode(200)
                .contentType(startsWith("application/schema+json"))
                .header("ETag", matchesPattern("\"[0-9a-f]{64}\""))
                .body("$schema", equalTo("https://json-schema.org/draft/2020-12/schema"))
                .body("properties.template", notNullValue());
    }

    // --- fixtures ----------------------------------------------------------------------------

    private static ValidatableResponse problem(io.restassured.response.Response response, int status, String title) {
        return response.then().statusCode(status)
                .contentType(startsWith(PROBLEM))
                .body("type", equalTo("about:blank"))
                .body("title", equalTo(title))
                .body("status", equalTo(status));
    }

    /** Inserts a workspace pinned to {@code template@version}, in any state, bypassing provisioning. */
    private static String workspace(String template, int version, String delta, long revision, String follow) {
        String key = "CFG" + SEQ.incrementAndGet();
        sql("""
                insert into workspace (id, key, name, template_id, template_version, config_delta, config_revision, follow)
                values (gen_random_uuid(), ?, ?, (select id from workflow_template where key = ?), ?, ?::jsonb, ?, ?)
                """, key, key, template, version, delta, revision, follow);
        return key;
    }

    /**
     * A tenant template version over {@code base@1} that renames To Do to Ready. {@code resolved}
     * null stores base's resolution with To Do renamed to Materialised, a document the chain does
     * not produce.
     */
    private static void tenantTemplate(String key, int version, int resolverVersion, String resolved) throws SQLException {
        if (version == 1) {
            sql("insert into workflow_template (id, tenant_id, key) values (gen_random_uuid(), ?::uuid, ?)",
                    UUID.randomUUID().toString(), key);
        }
        String document = """
                {"template": "%s", "version": %d, "name": "Acme", "extends": {"template": "base", "version": 1},
                 "states": {"todo": {"name": "Ready"}}}""".formatted(key, version);
        sql("""
                insert into workflow_template_version
                       (template_id, version, parent_id, parent_version, document, resolved, resolver_version, cause)
                select t.id, ?, b.id, 1, ?::jsonb,
                       coalesce(?::jsonb, jsonb_set(base.resolved, '{states,todo,name}', '"Materialised"')),
                       ?, '{"kind": "authored"}'::jsonb
                  from workflow_template t, workflow_template b
                  join workflow_template_version base on base.template_id = b.id and base.version = 1
                 where t.key = ? and b.key = 'base' and b.tenant_id is null
                """, version, document, resolved, resolverVersion, key);
    }

    private static void sql(String statement, Object... params) {
        Config config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
             PreparedStatement st = c.prepareStatement(statement)) {
            for (int i = 0; i < params.length; i++) {
                st.setObject(i + 1, params[i]);
            }
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
