// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.vectis.domain.TimeOrderedId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** VEC-94 against PostgreSQL 16: the workspace, board, column and item reads under {@code /api/v1}. */
@QuarkusTest
@TestProfile(ApiTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class WorkspaceReadApiTest {

    private static final String PROBLEM = "application/problem+json";
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** The scrum@1 projection: key, name, on the board. */
    private static final List<List<Object>> SCRUM_COLUMNS = List.of(
            List.of("backlog", "Backlog", true), List.of("todo", "To Do", true),
            List.of("in-progress", "In Progress", true), List.of("in-review", "In Review", true),
            List.of("done", "Done", true), List.of("no-go", "No Go", false));

    // --- GET /workspaces and /workspaces/{key} (AC1) -----------------------------------------

    @Test
    void aWorkspaceAnswersWithItsKeyNameAndTemplateAndNoId() {
        Seeded ws = workspace("kanban");
        given().when().get("/api/v1/workspaces/" + ws.key())
                .then().statusCode(200)
                .body("key", equalTo(ws.key()))
                .body("name", equalTo("Workspace " + ws.key()))
                .body("template.key", equalTo("kanban"))
                .body("template.version", equalTo(1))
                .body("keySet()", containsInAnyOrder("key", "name", "template"));
    }

    @Test
    void theListHoldsEveryWorkspaceNewestFirst() {
        Seeded older = workspace("scrum");
        Seeded newer = workspace("kanban");
        List<String> keys = given().when().get("/api/v1/workspaces")
                .then().statusCode(200)
                .body("find { it.key == '" + newer.key() + "' }.template.key", equalTo("kanban"))
                .body("find { it.key == '" + older.key() + "' }.name", equalTo("Workspace " + older.key()))
                .extract().jsonPath().getList("key", String.class);
        assertEquals(keys.indexOf(older.key()) - 1, keys.indexOf(newer.key()), keys.toString());
    }

    @Test
    void anUnknownWorkspaceIsA404OnEveryRead() {
        for (String path : List.of("", "/boards", "/items")) {
            problem(given().when().get("/api/v1/workspaces/NOPE" + path), 404, "Not Found")
                    .body("detail", equalTo("workspace NOPE does not exist"));
        }
    }

    // --- GET /workspaces/{key}/boards (AC2) ---------------------------------------------------

    @Test
    void theBoardsCarryTheirColumnsInOrdinalOrder() {
        Seeded ws = workspace("scrum");
        given().when().get("/api/v1/workspaces/" + ws.key() + "/boards")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].key", equalTo(ws.key()))
                .body("[0].name", equalTo("Board " + ws.key()))
                .body("[0].keySet()", containsInAnyOrder("key", "name", "columns"))
                .body("[0].columns.key", contains("backlog", "todo", "in-progress", "in-review", "done", "no-go"))
                .body("[0].columns.name", contains("Backlog", "To Do", "In Progress", "In Review", "Done", "No Go"))
                .body("[0].columns.ordinal", contains(0, 1, 2, 3, 4, 5))
                .body("[0].columns.onBoard", contains(true, true, true, true, true, false))
                .body("[0].columns[0].keySet()", containsInAnyOrder("key", "name", "ordinal", "onBoard"));
    }

    @Test
    void anotherWorkspacesBoardsAreNotListed() {
        Seeded ws = workspace("scrum");
        Seeded other = workspace("kanban");
        given().when().get("/api/v1/workspaces/" + ws.key() + "/boards")
                .then().statusCode(200)
                .body("name", contains("Board " + ws.key()))
                .body("name", not(hasItem("Board " + other.key())));
    }

    // --- GET /workspaces/{key}/items (AC3) ----------------------------------------------------

    @Test
    void itemsComeNewestFirstWithTheirColumnKey() {
        Seeded ws = workspace("scrum");
        List<UUID> ids = ids(3);
        // written out of id order, so the physical order is not the answer
        item(ws, ids.get(1), "T-2", "todo");
        item(ws, ids.get(2), "T-3", "done");
        item(ws, ids.get(0), "T-1", "no-go");

        given().when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200)
                .body("items.key", contains("T-3", "T-2", "T-1"))
                .body("items.column", contains("done", "todo", "no-go"))
                .body("items.board", everyItem(equalTo(ws.key())))
                .body("items[0].title", equalTo("Title T-3"))
                .body("items[0].rank", equalTo("r-T-3"))
                .body("items[0].fields.points", equalTo(3))
                .body("items[0].version", equalTo(1))
                .body("items[0]", not(hasKey("id")))
                .body("next", nullValue());
    }

    @Test
    void theCursorWalksEveryItemOncePageByPage() {
        Seeded ws = workspace("scrum");
        List<UUID> ids = ids(5);
        for (int i = ids.size() - 1; i >= 0; i--) {
            item(ws, ids.get(i), "P-" + (i + 1), "todo");
        }

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = given().queryParam("limit", 2);
            if (cursor != null) {
                request.queryParam("cursor", cursor);
            }
            var page = request.when().get("/api/v1/workspaces/" + ws.key() + "/items")
                    .then().statusCode(200).extract().jsonPath();
            seen.addAll(page.getList("items.key", String.class));
            cursor = page.getString("next");
            pages++;
        } while (cursor != null);

        assertEquals(List.of("P-5", "P-4", "P-3", "P-2", "P-1"), seen);
        assertEquals(3, pages);
    }

    @Test
    void aFullLastPageSaysThereIsNoNextOne() {
        Seeded ws = workspace("scrum");
        List<UUID> ids = ids(2);
        item(ws, ids.get(0), "F-1", "todo");
        item(ws, ids.get(1), "F-2", "todo");
        given().queryParam("limit", 2).when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200)
                .body("items.key", contains("F-2", "F-1"))
                .body("next", nullValue());
    }

    @Test
    void theDefaultPageIsFiftyItems() {
        Seeded ws = workspace("scrum");
        List<UUID> ids = ids(51);
        for (int i = 0; i < ids.size(); i++) {
            item(ws, ids.get(i), "D-" + i, "todo");
        }
        given().when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200)
                .body("items.size()", equalTo(50))
                .body("next", notNullValue());
    }

    @Test
    void theColumnFilterKeepsOnlyThatColumnsItems() {
        Seeded ws = workspace("scrum");
        List<UUID> ids = ids(3);
        item(ws, ids.get(0), "C-1", "todo");
        item(ws, ids.get(1), "C-2", "done");
        item(ws, ids.get(2), "C-3", "todo");
        given().queryParam("column", "todo").when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200)
                .body("items.key", contains("C-3", "C-1"))
                .body("items.column", everyItem(equalTo("todo")));
    }

    @Test
    void anotherWorkspacesItemsAreNotListed() {
        Seeded ws = workspace("scrum");
        Seeded other = workspace("scrum");
        List<UUID> ids = ids(2);
        item(ws, ids.get(0), "MINE-1", "todo");
        item(other, ids.get(1), "THEIRS-1", "todo");
        given().when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200)
                .body("items.key", contains("MINE-1"));
    }

    @Test
    void aSecondBoardFollowsTheFirstUnderTheNextFreeKey() {
        Seeded ws = workspace("scrum");
        board(ws.workspaceId(), "Support", List.of(List.of("triage", "Triage", true), List.of("closed", "Closed", true)));
        given().when().get("/api/v1/workspaces/" + ws.key() + "/boards")
                .then().statusCode(200)
                .body("key", contains(ws.key(), ws.key() + "-2"))
                .body("name", contains("Board " + ws.key(), "Support"))
                .body("[1].columns.key", contains("triage", "closed"))
                .body("[1].columns.ordinal", contains(0, 1));
    }

    @Test
    void theBoardFilterKeepsOnlyThatBoardsItemsAndNarrowsTheColumnToIt() {
        Seeded ws = workspace("scrum");
        UUID support = board(ws.workspaceId(), "Support", List.of(List.of("todo", "To Do", true)));
        List<UUID> ids = ids(3);
        item(ws, ids.get(0), "B-1", "todo");
        item(support, ids.get(1), "S-2", "todo");
        item(ws, ids.get(2), "B-3", "done");
        String path = "/api/v1/workspaces/" + ws.key() + "/items";

        given().queryParam("board", ws.key() + "-2").when().get(path)
                .then().statusCode(200)
                .body("items.key", contains("S-2"))
                .body("items.board", contains(ws.key() + "-2"));
        given().queryParam("board", ws.key()).queryParam("column", "todo").when().get(path)
                .then().statusCode(200)
                .body("items.key", contains("B-1"));
        given().queryParam("column", "todo").when().get(path)
                .then().statusCode(200)
                .body("items.key", contains("S-2", "B-1"));
    }

    // --- the problems of these reads (AC4) ----------------------------------------------------

    @Test
    void aBoardThatNamesNothingIsA404() {
        Seeded ws = workspace("scrum");
        Seeded other = workspace("scrum");
        problem(given().queryParam("board", other.key()).when().get("/api/v1/workspaces/" + ws.key() + "/items"),
                404, "Not Found")
                .body("detail", equalTo("workspace " + ws.key() + " has no board " + other.key()));
    }

    @Test
    void aColumnTheNamedBoardDoesNotHaveIsA404() {
        Seeded ws = workspace("scrum");
        board(ws.workspaceId(), "Support", List.of(List.of("triage", "Triage", true)));
        problem(given().queryParam("board", ws.key()).queryParam("column", "triage")
                        .when().get("/api/v1/workspaces/" + ws.key() + "/items"), 404, "Not Found")
                .body("detail", equalTo("workspace " + ws.key() + " has no column triage on board " + ws.key()));
    }

    @Test
    void aColumnThatNamesNothingIsA404() {
        Seeded ws = workspace("scrum");
        problem(given().queryParam("column", "blocked").when().get("/api/v1/workspaces/" + ws.key() + "/items"),
                404, "Not Found")
                .body("detail", equalTo("workspace " + ws.key() + " has no column blocked"));
    }

    @Test
    void aMalformedColumnKeyIsA400() {
        Seeded ws = workspace("scrum");
        problem(given().queryParam("column", "To Do").when().get("/api/v1/workspaces/" + ws.key() + "/items"),
                400, "Bad Request")
                .body("detail", equalTo("column is a column key; got 'To Do'"));
    }

    @Test
    void aLimitOutsideOneToTwoHundredIsA400() {
        Seeded ws = workspace("scrum");
        for (String limit : List.of("0", "201", "-1", "ten")) {
            problem(given().queryParam("limit", limit).when().get("/api/v1/workspaces/" + ws.key() + "/items"),
                    400, "Bad Request")
                    .body("detail", equalTo("limit is a whole number from 1 to 200; got '" + limit + "'"));
        }
        given().queryParam("limit", 200).when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200);
        given().queryParam("limit", 1).when().get("/api/v1/workspaces/" + ws.key() + "/items")
                .then().statusCode(200);
    }

    @Test
    void aCursorThisApiDidNotIssueIsA400() {
        Seeded ws = workspace("scrum");
        for (String cursor : List.of("not a cursor", "AAAA")) {
            problem(given().queryParam("cursor", cursor).when().get("/api/v1/workspaces/" + ws.key() + "/items"),
                    400, "Bad Request")
                    .body("detail", equalTo("cursor is not one this API issued: '" + cursor + "'"));
        }
    }

    // --- fixtures ----------------------------------------------------------------------------

    private record Seeded(String key, UUID workspaceId, UUID boardId) {
    }

    private static ValidatableResponse problem(io.restassured.response.Response response, int status, String title) {
        return response.then().statusCode(status)
                .contentType(startsWith(PROBLEM))
                .body("type", equalTo("about:blank"))
                .body("title", equalTo(title))
                .body("status", equalTo(status));
    }

    /**
     * A workspace pinned to {@code template@1} with one board holding the scrum@1 columns, written
     * in reverse ordinal order so the physical order is not the answer.
     */
    private static Seeded workspace(String template) {
        String key = "RD" + SEQ.incrementAndGet();
        UUID workspaceId = TimeOrderedId.next();
        sql("""
                insert into workspace (id, key, name, template_id, template_version)
                values (?, ?, ?, (select id from workflow_template where key = ? and tenant_id is null), 1)
                """, workspaceId, key, "Workspace " + key, template);
        return new Seeded(key, workspaceId, board(workspaceId, "Board " + key, SCRUM_COLUMNS));
    }

    /** A board whose columns are written in reverse ordinal order; the database gives it its key. */
    private static UUID board(UUID workspaceId, String name, List<List<Object>> columns) {
        UUID boardId = TimeOrderedId.next();
        sql("insert into board (id, workspace_id, name) values (?, ?, ?)", boardId, workspaceId, name);
        for (int ordinal = columns.size() - 1; ordinal >= 0; ordinal--) {
            List<Object> column = columns.get(ordinal);
            sql("insert into board_column (id, board_id, key, name, ordinal, on_board) values (?, ?, ?, ?, ?, ?)",
                    TimeOrderedId.next(), boardId, column.get(0), column.get(1), ordinal, column.get(2));
        }
        return boardId;
    }

    /** {@code n} ids in creation order. */
    private static List<UUID> ids(int n) {
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(TimeOrderedId.next());
        }
        return ids;
    }

    private static void item(Seeded ws, UUID id, String key, String column) {
        item(ws.boardId(), id, key, column);
    }

    private static void item(UUID boardId, UUID id, String key, String column) {
        sql("""
                insert into item (id, workspace_id, board_id, column_id, key, title, rank, fields)
                select ?, b.workspace_id, b.id, c.id, ?, ?, ?, ?::jsonb
                  from board b join board_column c on c.board_id = b.id
                 where b.id = ? and c.key = ?
                """, id, key, "Title " + key, "r-" + key, "{\"points\": " + key.substring(key.length() - 1) + "}",
                boardId, column);
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
            assertEquals(1, st.executeUpdate(), statement);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
