// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import io.vectis.domain.TimeOrderedId;
import io.vertx.core.json.JsonObject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * VEC-70 against PostgreSQL 18: item writes checked against the workspace's effective
 * configuration (the form vocabulary of ADR-VEC-03), on the shipped scrum@1 and stage-gate@1. Each
 * refusal is a 422 whose violations name the field as {@code fields.<key>} and carry the code a
 * client translates (ADR-VEC-05); a move takes the fields its target state needs, as one version
 * and one event. The rules themselves are pinned case by case by the command module's conformance
 * fixture; this checks they are applied on the HTTP surface.
 */
@QuarkusTest
@TestProfile(ApiTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class ItemFieldRulesApiTest {

    private static final String PROBLEM = "application/problem+json";
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final List<List<Object>> SCRUM_COLUMNS = List.of(
            List.of("backlog", "Backlog", true), List.of("todo", "To Do", true),
            List.of("in-progress", "In Progress", true), List.of("in-review", "In Review", true),
            List.of("done", "Done", true), List.of("no-go", "No Go", false));

    private static final List<List<Object>> STAGE_GATE_COLUMNS = List.of(
            List.of("idea", "Idea", true), List.of("scoping", "Scoping", true),
            List.of("gate-1", "Gate 1: go to development", true), List.of("development", "Development", true),
            List.of("gate-2", "Gate 2: go to launch", true), List.of("done", "Launched", true),
            List.of("no-go", "Killed", false));

    // --- create ----------------------------------------------------------------------------------

    @Test
    void aCreateWithoutATypeIsTheDefaultTypeWithItsDefaultsWritten() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        json().body("{\"title\": \"Sign in\"}").post(itemsPath(ws.key())).then().statusCode(201)
                .body("fields.type", equalTo("story"))
                .body("fields.priority", equalTo("medium"));
    }

    @Test
    void aBugWithoutADescriptionIsRefusedOnItsDescription() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        problem(json().body("{\"title\": \"Crash\", \"fields\": {\"type\": \"bug\"}}").post(itemsPath(ws.key())), 422)
                .body("violations", hasSize(1))
                .body("violations[0].field", equalTo("fields.description"))
                .body("violations[0].code", equalTo("required"))
                .body("violations[0].level", equalTo("workspace"))
                .body("violations[0].message", equalTo("a Bug has a Description"));
    }

    @Test
    void pointsOffTheScaleAreRefusedWithTheScaleCode() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        problem(json().body("{\"title\": \"t\", \"fields\": {\"type\": \"story\", \"storyPoints\": 4}}")
                .post(itemsPath(ws.key())), 422)
                .body("violations[0].field", equalTo("fields.storyPoints"))
                .body("violations[0].code", equalTo("scale"));
    }

    @Test
    void aTypeTheWorkspaceDoesNotHaveIsRefused() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        problem(json().body("{\"title\": \"t\", \"fields\": {\"type\": \"feature\"}}").post(itemsPath(ws.key())), 422)
                .body("violations[0].field", equalTo("fields.type"))
                .body("violations[0].code", equalTo("unknownType"));
    }

    @Test
    void aViolationWithoutACodeLeavesTheMemberOut() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        problem(json().body("{\"title\": \"t\", \"fields\": {\"\": 1}}").post(itemsPath(ws.key())), 422)
                .body("violations[0].field", equalTo("fields"))
                .body("violations[0]", not(hasKey("code")));
    }

    @Test
    void aCreateStraightIntoAStateThatNeedsAFieldIsRefusedWithoutIt() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        problem(json().body("{\"title\": \"New market\", \"column\": \"gate-1\", \"fields\": {\"type\": \"project\"}}")
                .post(itemsPath(ws.key())), 422)
                .body("violations[0].field", equalTo("fields.businessCase"))
                .body("violations[0].code", equalTo("requiredToEnter"));
    }

    // --- edit ------------------------------------------------------------------------------------

    @Test
    void anEditOffTheScaleIsRefusedAndWritesNothing() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        String item = create(ws, "{\"title\": \"t\", \"fields\": {\"storyPoints\": 5}}");
        problem(json().header("If-Match", "\"1\"").body("{\"fields\": {\"storyPoints\": 7}}").patch(itemPath(ws.key(), item)), 422)
                .body("violations[0].code", equalTo("scale"));
        given().get(itemPath(ws.key(), item)).then().body("version", equalTo(1)).body("fields.storyPoints", equalTo(5));
    }

    @Test
    void anItemsTypeIsNotChangedByAnEdit() {
        Seeded ws = workspace("scrum", SCRUM_COLUMNS);
        String item = create(ws, "{\"title\": \"t\"}");
        problem(json().header("If-Match", "\"1\"").body("{\"fields\": {\"type\": \"epic\"}}").patch(itemPath(ws.key(), item)), 422)
                .body("violations[0].field", equalTo("fields.type"))
                .body("violations[0].code", equalTo("type"));
    }

    @Test
    void aFieldTheCurrentStateNeedsCannotBeEmptied() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        String item = create(ws, "{\"title\": \"New market\", \"column\": \"gate-1\", \"fields\": {\"type\": \"project\", \"businessCase\": \"Grow\"}}");
        problem(json().header("If-Match", "\"1\"").body("{\"fields\": {\"businessCase\": null}}").patch(itemPath(ws.key(), item)), 422)
                .body("violations[0].field", equalTo("fields.businessCase"))
                .body("violations[0].code", equalTo("requiredToEnter"));
    }

    // --- move ------------------------------------------------------------------------------------

    @Test
    void aMoveIntoAStateThatNeedsAFieldIsRefusedWithoutIt() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        String item = create(ws, "{\"title\": \"New market\", \"column\": \"scoping\", \"fields\": {\"type\": \"project\"}}");
        problem(json().header("If-Match", "\"1\"").body("{\"column\": \"gate-1\"}").post(itemPath(ws.key(), item) + "/move"), 422)
                .body("violations[0].field", equalTo("fields.businessCase"))
                .body("violations[0].code", equalTo("requiredToEnter"))
                .body("violations[0].message", equalTo("Gate 1: go to development needs Business case"));
        assertEquals(List.of("item.created"), events(ws).stream().map(e -> e.getString("type")).toList());
    }

    @Test
    void aMoveThatBringsTheFieldMovesAndWritesItAsOneVersionAndOneEvent() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        String item = create(ws, "{\"title\": \"New market\", \"column\": \"scoping\", \"fields\": {\"type\": \"project\"}}");
        json().header("If-Match", "\"1\"").body("{\"column\": \"gate-1\", \"fields\": {\"businessCase\": \"Grow\"}}")
                .post(itemPath(ws.key(), item) + "/move")
                .then().statusCode(200)
                .header("ETag", "\"2\"")
                .body("column", equalTo("gate-1"))
                .body("fields.businessCase", equalTo("Grow"))
                .body("fields.type", equalTo("project"));
        List<JsonObject> events = events(ws);
        assertEquals(List.of("item.created", "item.moved"), events.stream().map(e -> e.getString("type")).toList());
        assertTrue(events.getLast().encode().contains("fields.businessCase"), events.getLast()::encode);
    }

    @Test
    void aMoveIntoAStateThatNeedsNothingGoesThrough() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        String item = create(ws, "{\"title\": \"New market\", \"fields\": {\"type\": \"project\"}}");
        json().header("If-Match", "\"1\"").body("{\"column\": \"scoping\"}").post(itemPath(ws.key(), item) + "/move")
                .then().statusCode(200).body("column", equalTo("scoping"));
    }

    @Test
    void aMilestoneWithoutABusinessCaseFieldEntersGateOne() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        String item = create(ws, "{\"title\": \"Pilot\", \"column\": \"scoping\", \"fields\": {\"type\": \"milestone\", \"targetLaunch\": \"2027-03-01\"}}");
        json().header("If-Match", "\"1\"").body("{\"column\": \"gate-1\"}").post(itemPath(ws.key(), item) + "/move")
                .then().statusCode(200).body("column", equalTo("gate-1"));
    }

    @Test
    void aMovesFieldsMustBeAnObject() {
        Seeded ws = workspace("stage-gate", STAGE_GATE_COLUMNS);
        String item = create(ws, "{\"title\": \"New market\", \"fields\": {\"type\": \"project\"}}");
        problem(json().header("If-Match", "\"1\"").body("{\"column\": \"scoping\", \"fields\": [1]}")
                .post(itemPath(ws.key(), item) + "/move"), 400);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Seeded(String key, UUID workspaceId, UUID boardId) {
    }

    private static String itemsPath(String ws) {
        return "/api/v1/workspaces/" + ws + "/items";
    }

    private static String itemPath(String ws, String item) {
        return itemsPath(ws) + "/" + item;
    }

    private static RequestSpecification json() {
        return given().contentType("application/json");
    }

    private static String create(Seeded ws, String body) {
        return json().body(body).post(itemsPath(ws.key())).then().statusCode(201).extract().path("key");
    }

    private static ValidatableResponse problem(Response response, int status) {
        return response.then().statusCode(status)
                .contentType(startsWith(PROBLEM))
                .body("status", equalTo(status));
    }

    /** A workspace pinned to the built-in {@code template}@1, with one board holding {@code columns}. */
    private static Seeded workspace(String template, List<List<Object>> columns) {
        String key = "FR" + SEQ.incrementAndGet();
        UUID workspaceId = TimeOrderedId.next();
        sql("""
                insert into workspace (id, key, name, template_id, template_version)
                values (?, ?, ?, (select id from workflow_template where key = ? and tenant_id is null), 1)
                """, workspaceId, key, "Workspace " + key, template);
        UUID boardId = TimeOrderedId.next();
        sql("insert into board (id, workspace_id, name) values (?, ?, ?)", boardId, workspaceId, "Board " + key);
        for (int ordinal = 0; ordinal < columns.size(); ordinal++) {
            List<Object> column = columns.get(ordinal);
            sql("insert into board_column (id, board_id, key, name, ordinal, on_board) values (?, ?, ?, ?, ?, ?)",
                    TimeOrderedId.next(), boardId, column.get(0), column.get(1), ordinal, column.get(2));
        }
        return new Seeded(key, workspaceId, boardId);
    }

    private static List<JsonObject> events(Seeded ws) {
        return query("select payload from workspace_event where workspace_id = ? order by seq", ws.workspaceId())
                .stream().map(JsonObject::new).toList();
    }

    private static Connection connection() throws SQLException {
        Config config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
    }

    private static void sql(String statement, Object... params) {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(statement)) {
            parameters(st, params);
            assertTrue(st.executeUpdate() >= 1, statement);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> query(String statement, Object... params) {
        try (Connection c = connection(); PreparedStatement st = c.prepareStatement(statement)) {
            parameters(st, params);
            List<String> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void parameters(PreparedStatement st, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            st.setObject(i + 1, params[i]);
        }
    }
}
