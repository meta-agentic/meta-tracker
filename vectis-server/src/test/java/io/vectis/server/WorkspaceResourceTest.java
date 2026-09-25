package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vectis.domain.Item;
import io.vectis.persistence.ItemRepository;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The workspace API as the SPA's client reads it. Field names are asserted literally:
 * they are the contract {@code web/src/api/wire.ts} decodes, so a rename here is a
 * breaking change there.
 */
@QuarkusTest
class WorkspaceResourceTest {

    private static final AtomicInteger KEY_SEQ = new AtomicInteger();

    @Inject ItemRepository items;

    private static String freshKey() {
        return "WR" + KEY_SEQ.incrementAndGet();
    }

    private static JsonPath provision(String key, String template) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("key", key, "name", "Team " + key, "template", template))
                .when().post("/api/v1/workspaces")
                .then().statusCode(201)
                .extract().jsonPath();
    }

    @Test
    void templatesListTheCatalogue() {
        given().when().get("/api/v1/templates")
                .then().statusCode(200)
                .body("id", contains("kanban", "scrum"))
                .body("find { it.id == 'kanban' }.columns", contains("To Do", "In Progress", "Done"))
                .body("find { it.id == 'scrum' }.issueTypes", contains("story", "task", "bug", "epic"));
    }

    @Test
    void provisioningAScrumWorkspaceCreatesItsBoard() {
        String key = freshKey();

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("key", key, "name", "Platform", "template", "scrum"))
                .when().post("/api/v1/workspaces")
                .then().statusCode(201)
                .header("Location", endsWith("/api/v1/workspaces/" + key))
                .body("workspace.key", equalTo(key))
                .body("workspace.name", equalTo("Platform"))
                .body("boards", hasSize(1))
                .body("boards[0].columns.name", contains("Backlog", "To Do", "In Progress", "In Review", "Done"))
                .body("boards[0].columns.position", contains(0, 1, 2, 3, 4));
    }

    @Test
    void readsReturnTheWireShapesTheClientDecodes() {
        String key = freshKey();
        JsonPath created = provision(key, "kanban");
        String workspaceId = created.getString("workspace.id");

        given().when().get("/api/v1/workspaces/" + key)
                .then().statusCode(200)
                .body("id", equalTo(workspaceId))
                .body("key", equalTo(key))
                .body("name", equalTo("Team " + key))
                .body("keySet()", containsInAnyOrder("id", "key", "name"));

        given().when().get("/api/v1/workspaces/" + key + "/boards")
                .then().statusCode(200)
                .body("", hasSize(1))
                .body("[0].keySet()", containsInAnyOrder("id", "workspaceId", "name", "columns"))
                .body("[0].workspaceId", equalTo(workspaceId))
                .body("[0].columns[0].keySet()", containsInAnyOrder("id", "name", "position"))
                .body("[0].columns.name", contains("To Do", "In Progress", "Done"));
    }

    @Test
    void itemsCarryRankFieldsAndANullSprint() {
        String key = freshKey();
        JsonPath created = provision(key, "kanban");
        UUID workspaceId = UUID.fromString(created.getString("workspace.id"));
        UUID boardId = UUID.fromString(created.getString("boards[0].id"));
        UUID todo = UUID.fromString(created.getString("boards[0].columns[0].id"));

        Item epic = Item.create(workspaceId, boardId, todo, key + "-1", "Checkout", "m")
                .withFields(Map.of("type", "epic", "color", "#4f46e5"));
        Item task = Item.create(workspaceId, boardId, todo, key + "-2", "Wire the form", "g")
                .withFields(Map.of("type", "task", "parentId", epic.id().toString(),
                        "storyPoints", 3, "startDate", "2026-09-01", "dueDate", "2026-09-12"));
        items.insertAll(List.of(epic, task)).await().indefinitely();

        given().when().get("/api/v1/workspaces/" + key + "/items")
                .then().statusCode(200)
                .body("key", contains(key + "-2", key + "-1"))
                .body("[0].keySet()", containsInAnyOrder(
                        "id", "workspaceId", "boardId", "columnId", "key", "title", "rank", "fields", "sprintId"))
                .body("[0].rank", equalTo("g"))
                .body("[0].sprintId", nullValue())
                .body("[0].fields.parentId", equalTo(epic.id().toString()))
                .body("[0].fields.storyPoints", equalTo(3))
                .body("[0].fields.startDate", equalTo("2026-09-01"))
                .body("[1].fields.type", equalTo("epic"));
    }

    @Test
    void anEmptyWorkspaceHasAnEmptyItemList() {
        String key = freshKey();
        provision(key, "kanban");

        given().when().get("/api/v1/workspaces/" + key + "/items")
                .then().statusCode(200).body("", hasSize(0));
    }

    @Test
    void everyInvalidFieldIsReportedAtOnce() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("key", "lower", "name", "  ", "template", "waterfall"))
                .when().post("/api/v1/workspaces")
                .then().statusCode(400)
                .body("error", equalTo("validation"))
                .body("violations.field", containsInAnyOrder("key", "name", "template"))
                .body("violations.find { it.field == 'template' }.message", equalTo(
                        "unknown template 'waterfall'; expected one of: kanban, scrum"));
    }

    @Test
    void missingFieldsAreViolationsNotServerErrors() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/v1/workspaces")
                .then().statusCode(400)
                .body("error", equalTo("validation"))
                .body("violations.field", containsInAnyOrder("key", "name", "template"));
    }

    @Test
    void aBodyThatIsNotJsonIsAValidationError() {
        given()
                .contentType(ContentType.JSON)
                .body("{not json")
                .when().post("/api/v1/workspaces")
                .then().statusCode(400)
                .body("error", equalTo("validation"))
                .body("violations.field", contains("body"));
    }

    @Test
    void aWrongTypedFieldIsAValidationError() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"key\": {\"nested\": true}, \"name\": \"X\", \"template\": \"kanban\"}")
                .when().post("/api/v1/workspaces")
                .then().statusCode(400)
                .body("error", equalTo("validation"))
                .body("violations.field", contains("key"));
    }

    @Test
    void aTakenKeyIsAConflict() {
        String key = freshKey();
        provision(key, "kanban");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("key", key, "name", "Again", "template", "scrum"))
                .when().post("/api/v1/workspaces")
                .then().statusCode(409)
                .body("error", equalTo("conflict"))
                .body("violations.field", contains("key"));

        given().when().get("/api/v1/workspaces/" + key + "/boards")
                .then().statusCode(200).body("", hasSize(1));
    }

    @Test
    void unknownWorkspacesAreNotFoundOnEveryRead() {
        for (String path : List.of("", "/boards", "/items")) {
            given().when().get("/api/v1/workspaces/NOPE" + path)
                    .then().statusCode(404)
                    .body("error", equalTo("not_found"));
        }
    }
}
