// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * VEC-16 against PostgreSQL 18: the item writes of {@code /api/v1} through the command layer, each
 * status of ADR-VEC-05 for each write, the refusal on a workspace bound to a backlog source, two
 * writers racing on one version, and a workspace that cannot reach another's items.
 */
@QuarkusTest
@TestProfile(ApiTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class ItemWriteApiTest {

    private static final String PROBLEM = "application/problem+json";
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** The scrum@1 projection: key, name, on the board. */
    private static final List<List<Object>> SCRUM_COLUMNS = List.of(
            List.of("backlog", "Backlog", true), List.of("todo", "To Do", true),
            List.of("in-progress", "In Progress", true), List.of("in-review", "In Review", true),
            List.of("done", "Done", true), List.of("no-go", "No Go", false));

    /**
     * The three changes to an existing item, each with a request that is well formed and keeps the
     * rules. {@code writer} makes two writers' changes differ, so that neither is a no-op after the
     * other.
     */
    enum Write {
        EDIT(200) {
            Response send(RequestSpecification request, String ws, String item, int writer) {
                return request.contentType("application/json").body("{\"title\": \"Edited by " + writer + "\"}")
                        .patch(itemPath(ws, item));
            }
        },
        MOVE(200) {
            Response send(RequestSpecification request, String ws, String item, int writer) {
                return request.contentType("application/json")
                        .body("{\"column\": \"" + (writer == 1 ? "done" : "in-progress") + "\"}")
                        .post(itemPath(ws, item) + "/move");
            }
        },
        DELETE(204) {
            Response send(RequestSpecification request, String ws, String item, int writer) {
                return request.delete(itemPath(ws, item));
            }
        };

        final int success;

        Write(int success) {
            this.success = success;
        }

        abstract Response send(RequestSpecification request, String ws, String item, int writer);

        Response send(RequestSpecification request, String ws, String item) {
            return send(request, ws, item, 1);
        }
    }

    // --- GET .../items/{item} ------------------------------------------------------------------

    @Test
    void aCreatedItemReadsBackWithItsVersionAsTheETag() {
        Seeded ws = workspace();
        String item = create(ws, "Read back");
        given().get(itemPath(ws.key(), item)).then().statusCode(200)
                .header("ETag", "\"1\"")
                .body("key", equalTo(item))
                .body("title", equalTo("Read back"))
                .body("version", equalTo(1));
    }

    @Test
    void anUnknownItemIsA404() {
        Seeded ws = workspace();
        problem(given().get(itemPath(ws.key(), ws.key() + "-404")), 404)
                .body("detail", equalTo("workspace " + ws.key() + " has no item " + ws.key() + "-404"));
    }

    @Test
    void anItemOfAnotherWorkspaceIsNotReadThroughThisOne() {
        Seeded mine = workspace();
        Seeded other = workspace();
        String item = create(mine, "Mine");
        problem(given().get(itemPath(other.key(), item)), 404);
    }

    // --- POST .../items ------------------------------------------------------------------------

    @Test
    void createAnswers201WithTheItemItsLocationAndItsETag() {
        Seeded ws = workspace();
        json().body("{\"title\": \"Created\"}").post(itemsPath(ws.key()))
                .then().statusCode(201)
                .header("Location", endsWith(itemPath(ws.key(), ws.key() + "-1")))
                .header("ETag", "\"1\"")
                .body("key", equalTo(ws.key() + "-1"))
                .body("title", equalTo("Created"))
                .body("version", equalTo(1))
                .body("sprint", nullValue())
                .body("keySet()", containsInAnyOrder("key", "title", "board", "column", "rank", "fields", "version",
                        "sprint"));
    }

    @Test
    void theLocationOfACreatedItemReadsIt() {
        Seeded ws = workspace();
        String location = json().body("{\"title\": \"Located\"}").post(itemsPath(ws.key()))
                .then().statusCode(201).extract().header("Location");
        given().get(location).then().statusCode(200).body("title", equalTo("Located"));
    }

    @Test
    void eachCreateMintsTheWorkspacesNextKey() {
        Seeded ws = workspace();
        create(ws, "First");
        assertEquals(ws.key() + "-2", create(ws, "Second"));
    }

    @Test
    void createPutsTheItemOnTheFirstBoardInItsFirstColumnUnlessTold() {
        Seeded ws = workspace();
        json().body("{\"title\": \"Defaults\"}").post(itemsPath(ws.key())).then().statusCode(201)
                .body("board", equalTo(ws.key()))
                .body("column", equalTo("backlog"))
                .body("rank", equalTo("00000001"));
    }

    @Test
    void createPutsTheItemInTheNamedBoardColumnAndRank() {
        Seeded ws = workspace();
        board(ws.workspaceId(), "Support", List.of(List.of("triage", "Triage", true), List.of("closed", "Closed", true)));
        json().body("{\"title\": \"Placed\", \"board\": \"" + ws.key() + "-2\", \"column\": \"closed\", \"rank\": \"m\"}")
                .post(itemsPath(ws.key())).then().statusCode(201)
                .body("board", equalTo(ws.key() + "-2"))
                .body("column", equalTo("closed"))
                .body("rank", equalTo("m"));
    }

    @Test
    void createStoresTheFieldsAndLeavesOutOneSetToNull() {
        Seeded ws = workspace();
        json().body("{\"title\": \"Fielded\", \"fields\": {\"storyPoints\": 3, \"labels\": [\"api\"], \"gone\": null}}")
                .post(itemsPath(ws.key())).then().statusCode(201)
                .body("fields.storyPoints", equalTo(3))
                .body("fields.labels", equalTo(List.of("api")))
                .body("fields", not(hasKey("gone")));
    }

    @Test
    void createAppendsOneItemCreatedEventTaggedWithTheClientsOrigin() {
        Seeded ws = workspace();
        json().header("Vectis-Origin", "tab-7f3a").body("{\"title\": \"Evented\"}").post(itemsPath(ws.key()))
                .then().statusCode(201);
        List<JsonObject> events = events(ws);
        assertEquals(1, events.size());
        assertEquals("item.created", events.getFirst().getString("type"));
        assertEquals("tab-7f3a", events.getFirst().getString("origin"));
        assertEquals(1L, events.getFirst().getJsonObject("item").getLong("version"));
    }

    @Test
    void aWriteFromAClientThatDoesNotNameItselfIsTaggedWithTheChannel() {
        Seeded ws = workspace();
        create(ws, "Untagged");
        assertEquals("api", events(ws).getFirst().getString("origin"));
    }

    @Test
    void createOnAnUnknownWorkspaceIsA404() {
        problem(json().body("{\"title\": \"Lost\"}").post(itemsPath("NOPE")), 404)
                .body("detail", equalTo("workspace NOPE does not exist"));
    }

    static Stream<Arguments> refusedCreates() {
        return Stream.of(
                Arguments.of("{}", "title"),
                Arguments.of("{\"title\": \"  \"}", "title"),
                Arguments.of("{\"title\": \"t\", \"board\": \"NOPE\"}", "board"),
                Arguments.of("{\"title\": \"t\", \"column\": \"nowhere\"}", "column"),
                Arguments.of("{\"title\": \"t\", \"rank\": \"Not A Rank\"}", "rank"),
                Arguments.of("{\"title\": \"t\", \"fields\": {\"\": 1}}", "fields"));
    }

    @ParameterizedTest
    @MethodSource("refusedCreates")
    void aCreateThatBreaksARuleIsA422NamingTheField(String body, String field) {
        Seeded ws = workspace();
        problem(json().body(body).post(itemsPath(ws.key())), 422)
                .body("violations.field", equalTo(List.of(field)))
                .body("violations.level", equalTo(List.of("workspace")));
        assertEquals(List.of(), events(ws), "nothing written");
    }

    @Test
    void aCreateThatBreaksSeveralRulesNamesEveryOne() {
        Seeded ws = workspace();
        problem(json().body("{\"title\": \"\", \"rank\": \"R\", \"column\": \"nowhere\"}").post(itemsPath(ws.key())), 422)
                .body("violations.field", containsInAnyOrder("title", "rank", "column"));
    }

    static Stream<Arguments> malformedBodies() {
        return Stream.of(
                Arguments.of("application/json", "not json"),
                Arguments.of("application/json", "[\"a list\"]"),
                Arguments.of("application/json", ""),
                Arguments.of("application/json", "{\"title\": 7}"),
                Arguments.of("application/json", "{\"title\": \"t\", \"fields\": [1]}"),
                Arguments.of("application/json", "{\"title\": \"t\", \"key\": \"X-1\"}"),
                Arguments.of("text/plain", "{\"title\": \"t\"}"));
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void aCreateThatIsNotTheRightJsonIsA400(String contentType, String body) {
        Seeded ws = workspace();
        problem(given().contentType(contentType).body(body).post(itemsPath(ws.key())), 400);
        assertEquals(List.of(), events(ws), "nothing written");
    }


    // --- PATCH .../items/{item} ----------------------------------------------------------------

    @Test
    void anEditAnswersTheItemAtItsNewVersion() {
        Seeded ws = workspace();
        String item = create(ws, "Before");
        json().header("If-Match", "\"1\"").body("{\"title\": \"After\"}").patch(itemPath(ws.key(), item))
                .then().statusCode(200)
                .header("ETag", "\"2\"")
                .body("title", equalTo("After"))
                .body("version", equalTo(2));
    }

    @Test
    void anEditMergesTheFieldsPatch() {
        Seeded ws = workspace();
        String item = json().body("{\"title\": \"t\", \"fields\": {\"keep\": 1, \"change\": 1, \"drop\": 1}}")
                .post(itemsPath(ws.key())).then().statusCode(201).extract().path("key");
        json().header("If-Match", "\"1\"").body("{\"fields\": {\"change\": 2, \"drop\": null, \"add\": \"new\"}}")
                .patch(itemPath(ws.key(), item)).then().statusCode(200)
                .body("title", equalTo("t"))
                .body("fields.keep", equalTo(1))
                .body("fields.change", equalTo(2))
                .body("fields.add", equalTo("new"))
                .body("fields", not(hasKey("drop")));
    }

    @Test
    void anEditMergesANestedObjectIntoTheStoredOne() {
        Seeded ws = workspace();
        String item = json().body("{\"title\": \"t\", \"fields\": {\"estimate\": {\"extension\": 0.5, \"intension\": -0.1}}}")
                .post(itemsPath(ws.key())).then().statusCode(201).extract().path("key");
        json().header("If-Match", "\"1\"").body("{\"fields\": {\"estimate\": {\"intension\": 0.2}}}")
                .patch(itemPath(ws.key(), item)).then().statusCode(200)
                .body("fields.estimate.extension", equalTo(0.5f))
                .body("fields.estimate.intension", equalTo(0.2f));
    }

    @Test
    void anEditAppendsOneItemUpdatedEventNamingWhatChanged() {
        Seeded ws = workspace();
        String item = create(ws, "Before");
        json().header("If-Match", "\"1\"").body("{\"title\": \"After\"}").patch(itemPath(ws.key(), item))
                .then().statusCode(200);
        List<JsonObject> events = events(ws);
        assertEquals(2, events.size());
        assertEquals("item.updated", events.getLast().getString("type"));
        assertEquals(List.of("title"), events.getLast().getJsonArray("changed").getList());
        assertEquals(2L, events.getLast().getJsonObject("item").getLong("version"));
    }

    @Test
    void anEditThatChangesNothingKeepsTheVersionAndAppendsNoEvent() {
        Seeded ws = workspace();
        String item = create(ws, "Same");
        json().header("If-Match", "\"1\"").body("{\"title\": \"Same\"}").patch(itemPath(ws.key(), item))
                .then().statusCode(200).header("ETag", "\"1\"");
        assertEquals(1, events(ws).size());
    }

    @Test
    void anEditSentAsAMergePatchIsRead() {
        Seeded ws = workspace();
        String item = create(ws, "Before");
        given().contentType("application/merge-patch+json").header("If-Match", "\"1\"").body("{\"title\": \"After\"}")
                .patch(itemPath(ws.key(), item)).then().statusCode(200).body("title", equalTo("After"));
    }

    @Test
    void anEditNamingAMemberItDoesNotTakeIsA400() {
        Seeded ws = workspace();
        String item = create(ws, "Edit");
        problem(json().header("If-Match", "\"1\"").body("{\"column\": \"done\"}").patch(itemPath(ws.key(), item)), 400)
                .body("detail", equalTo("this write takes fields, title; got column"));
    }

    @Test
    void anEditWithABlankTitleIsA422() {
        Seeded ws = workspace();
        String item = create(ws, "Edit");
        problem(json().header("If-Match", "\"1\"").body("{\"title\": \" \"}").patch(itemPath(ws.key(), item)), 422)
                .body("violations.field", equalTo(List.of("title")));
        assertEquals(1, version(ws, item));
    }

    // --- POST .../items/{item}/move ------------------------------------------------------------

    @Test
    void aMoveAnswersTheItemInItsNewColumnAtItsNewRank() {
        Seeded ws = workspace();
        String item = create(ws, "Moving");
        json().header("If-Match", "\"1\"").body("{\"column\": \"in-progress\", \"rank\": \"k\"}")
                .post(itemPath(ws.key(), item) + "/move").then().statusCode(200)
                .header("ETag", "\"2\"")
                .body("column", equalTo("in-progress"))
                .body("rank", equalTo("k"))
                .body("version", equalTo(2));
    }

    @Test
    void aMoveWithoutARankKeepsTheItemsRank() {
        Seeded ws = workspace();
        String item = create(ws, "Moving");
        json().header("If-Match", "\"1\"").body("{\"column\": \"todo\"}").post(itemPath(ws.key(), item) + "/move")
                .then().statusCode(200).body("rank", equalTo("00000001"));
    }

    @Test
    void aMoveAppendsOneItemMovedEvent() {
        Seeded ws = workspace();
        String item = create(ws, "Moving");
        json().header("If-Match", "\"1\"").body("{\"column\": \"todo\"}").post(itemPath(ws.key(), item) + "/move")
                .then().statusCode(200);
        List<JsonObject> events = events(ws);
        assertEquals(2, events.size());
        assertEquals("item.moved", events.getLast().getString("type"));
    }

    @Test
    void aMoveWithoutAColumnIsA422() {
        Seeded ws = workspace();
        String item = create(ws, "Moving");
        problem(json().header("If-Match", "\"1\"").body("{\"rank\": \"k\"}").post(itemPath(ws.key(), item) + "/move"), 422)
                .body("violations.field", equalTo(List.of("column")));
    }

    @Test
    void aMoveToAColumnTheItemsBoardDoesNotHaveIsA422() {
        Seeded ws = workspace();
        board(ws.workspaceId(), "Support", List.of(List.of("triage", "Triage", true)));
        String item = create(ws, "Moving");
        problem(json().header("If-Match", "\"1\"").body("{\"column\": \"triage\"}")
                .post(itemPath(ws.key(), item) + "/move"), 422)
                .body("violations.field", equalTo(List.of("column")));
        assertEquals(1, version(ws, item));
    }

    // --- DELETE .../items/{item} ---------------------------------------------------------------

    @Test
    void aDeleteAnswers204AndTheItemIsGone() {
        Seeded ws = workspace();
        String item = create(ws, "Deleting");
        given().header("If-Match", "\"1\"").delete(itemPath(ws.key(), item)).then().statusCode(204);
        problem(given().get(itemPath(ws.key(), item)), 404);
    }

    @Test
    void aDeleteAppendsATombstoneOneVersionUp() {
        Seeded ws = workspace();
        String item = create(ws, "Deleting");
        given().header("If-Match", "\"1\"").delete(itemPath(ws.key(), item)).then().statusCode(204);
        JsonObject tombstone = events(ws).getLast();
        assertEquals("item.deleted", tombstone.getString("type"));
        assertEquals(2L, tombstone.getJsonObject("item").getLong("version"));
    }

    // --- the problems every change to an existing item shares ----------------------------------

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeAtAStaleVersionIsA412AndChangesNothing(Write write) {
        Seeded ws = workspace();
        String item = create(ws, "Stale");
        problem(write.send(given().header("If-Match", "\"7\""), ws.key(), item), 412)
                .body("detail", equalTo("item " + item + " is at version 1, not 7"));
        assertEquals(1, version(ws, item));
        assertEquals(1, events(ws).size());
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeWithoutIfMatchIsA400(Write write) {
        Seeded ws = workspace();
        String item = create(ws, "Unconditional");
        problem(write.send(given(), ws.key(), item), 400);
        assertEquals(1, version(ws, item));
    }

    static Stream<Arguments> malformedIfMatch() {
        return Stream.of(Write.values()).flatMap(write -> Stream.of("1", "W/\"1\"", "*", "\"1\", \"2\"", "\"one\"", "\"\"")
                .map(header -> Arguments.of(write, header)));
    }

    @ParameterizedTest
    @MethodSource("malformedIfMatch")
    void aChangeWhoseIfMatchIsNotOneVersionIsA400(Write write, String header) {
        Seeded ws = workspace();
        String item = create(ws, "Malformed");
        problem(write.send(given().header("If-Match", header), ws.key(), item), 400);
        assertEquals(1, version(ws, item));
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeToAnUnknownItemIsA404(Write write) {
        Seeded ws = workspace();
        problem(write.send(given().header("If-Match", "\"1\""), ws.key(), ws.key() + "-404"), 404)
                .body("detail", equalTo("workspace " + ws.key() + " has no item " + ws.key() + "-404"));
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeInAnUnknownWorkspaceIsA404(Write write) {
        problem(write.send(given().header("If-Match", "\"1\""), "NOPE", "NOPE-1"), 404)
                .body("detail", equalTo("workspace NOPE does not exist"));
    }

    // --- a workspace bound to a backlog source (AC4) -------------------------------------------

    @Test
    void anOwnedWorkspaceSaysItIsNotReadOnly() {
        given().get("/api/v1/workspaces/" + workspace().key()).then().statusCode(200).body("readOnly", equalTo(false));
    }

    @Test
    void aBoundWorkspaceSaysItIsReadOnly() {
        given().get("/api/v1/workspaces/" + bound().key()).then().statusCode(200).body("readOnly", equalTo(true));
    }

    @Test
    void aCreateOnABoundWorkspaceIsA409() {
        Seeded ws = bound();
        problem(json().body("{\"title\": \"Refused\"}").post(itemsPath(ws.key())), 409)
                .body("detail", equalTo("workspace " + ws.key() + " is read-only until write-back: its items come"
                        + " from the backlog source vault, which stays their system of record"));
        assertEquals(List.of(), events(ws));
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeOnABoundWorkspaceIsA409AndChangesNothing(Write write) {
        Seeded ws = workspace();
        String item = create(ws, "Imported");
        bind(ws);
        problem(write.send(given().header("If-Match", "\"1\""), ws.key(), item), 409)
                .body("detail", startsWith("workspace " + ws.key() + " is read-only until write-back"));
        assertEquals(1, version(ws, item));
        assertEquals(1, events(ws).size());
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeOnABoundWorkspaceIsA409EvenWithoutIfMatch(Write write) {
        Seeded ws = workspace();
        String item = create(ws, "Imported");
        bind(ws);
        problem(write.send(given(), ws.key(), item), 409);
    }

    @Test
    void aCreateThatBreaksTheRulesOnABoundWorkspaceIsStillA409() {
        problem(json().body("{\"title\": \"\"}").post(itemsPath(bound().key())), 409);
    }

    // --- two writers, one version (AC5) --------------------------------------------------------

    /**
     * A third connection holds the workspace row, so both requests are in flight and queued on its
     * lock before either writes; released, they write one after the other.
     */
    @ParameterizedTest
    @EnumSource(Write.class)
    void twoWritersWithTheSameVersionGetOneSuccessAndOne412(Write write) throws Exception {
        Seeded ws = workspace();
        String item = create(ws, "Raced");
        List<Integer> statuses;
        try (Connection blocker = connection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("select 1 from workspace where id = ? for update")) {
                lock.setObject(1, ws.workspaceId());
                lock.executeQuery().close();
            }
            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                    () -> write.send(given().header("If-Match", "\"1\""), ws.key(), item, 1).statusCode());
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                    () -> write.send(given().header("If-Match", "\"1\""), ws.key(), item, 2).statusCode());
            awaitLockWaiters(2);
            blocker.commit();
            statuses = Stream.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)).sorted().toList();
        }
        assertEquals(Stream.of(write.success, 412).sorted().toList(), statuses);
        assertEquals(2, events(ws).size(), "the creation and exactly one write");
    }

    // --- one workspace cannot reach another's items --------------------------------------------

    @ParameterizedTest
    @EnumSource(Write.class)
    void aChangeThroughAnotherWorkspaceDoesNotReachTheItem(Write write) {
        Seeded mine = workspace();
        Seeded other = workspace();
        String item = create(mine, "Mine");
        problem(write.send(given().header("If-Match", "\"1\""), other.key(), item), 404);
        assertEquals(1, version(mine, item));
        assertEquals(1, events(mine).size());
        assertEquals(List.of(), events(other));
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void aKeyTwoWorkspacesHoldIsChangedOnlyInTheNamedOne(Write write) {
        Seeded mine = workspace();
        Seeded other = workspace();
        item(mine, "SHARED-1");
        item(other, "SHARED-1");
        write.send(given().header("If-Match", "\"1\""), other.key(), "SHARED-1").then().statusCode(write.success);
        assertEquals(1, version(mine, "SHARED-1"));
        assertEquals(List.of(), events(mine));
    }

    // --- fixtures ------------------------------------------------------------------------------

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

    private static String create(Seeded ws, String title) {
        return json().body(new JsonObject().put("title", title).encode()).post(itemsPath(ws.key()))
                .then().statusCode(201).extract().path("key");
    }

    private static ValidatableResponse problem(Response response, int status) {
        return response.then().statusCode(status)
                .contentType(startsWith(PROBLEM))
                .body("type", equalTo("about:blank"))
                .body("status", equalTo(status));
    }

    /** A workspace pinned to scrum@1 with one board holding the scrum@1 columns. */
    private static Seeded workspace() {
        String key = "WR" + SEQ.incrementAndGet();
        UUID workspaceId = TimeOrderedId.next();
        sql("""
                insert into workspace (id, key, name, template_id, template_version)
                values (?, ?, ?, (select id from workflow_template where key = 'scrum' and tenant_id is null), 1)
                """, workspaceId, key, "Workspace " + key);
        return new Seeded(key, workspaceId, board(workspaceId, "Board " + key, SCRUM_COLUMNS));
    }

    /** A workspace bound to the vault, as the import leaves it. */
    private static Seeded bound() {
        Seeded ws = workspace();
        bind(ws);
        return ws;
    }

    private static void bind(Seeded ws) {
        sql("update workspace set source = 'vault' where id = ?", ws.workspaceId());
    }

    private static UUID board(UUID workspaceId, String name, List<List<Object>> columns) {
        UUID boardId = TimeOrderedId.next();
        sql("insert into board (id, workspace_id, name) values (?, ?, ?)", boardId, workspaceId, name);
        for (int ordinal = 0; ordinal < columns.size(); ordinal++) {
            List<Object> column = columns.get(ordinal);
            sql("insert into board_column (id, board_id, key, name, ordinal, on_board) values (?, ?, ?, ?, ?, ?)",
                    TimeOrderedId.next(), boardId, column.get(0), column.get(1), ordinal, column.get(2));
        }
        return boardId;
    }

    /** An item written straight into the database, at version 1, with no event. */
    private static void item(Seeded ws, String key) {
        sql("""
                insert into item (id, workspace_id, board_id, column_id, key, title, rank)
                select ?, b.workspace_id, b.id, c.id, ?, ?, 'r'
                  from board b join board_column c on c.board_id = b.id
                 where b.id = ? and c.key = 'backlog'
                """, TimeOrderedId.next(), key, "Title " + key, ws.boardId());
    }

    private static long version(Seeded ws, String item) {
        List<String> found = query("select version::text from item where workspace_id = ? and key = ?",
                ws.workspaceId(), item);
        assertEquals(1, found.size(), "one item " + item + " in " + ws.key());
        return Long.parseLong(found.getFirst());
    }

    private static List<JsonObject> events(Seeded ws) {
        return query("select payload from workspace_event where workspace_id = ? order by seq", ws.workspaceId())
                .stream().map(JsonObject::new).toList();
    }

    /** Waits until {@code n} sessions of this database wait on a lock. */
    private static void awaitLockWaiters(int n) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            int waiting = Integer.parseInt(query("""
                    select count(*)::text from pg_stat_activity
                     where datname = current_database() and wait_event_type = 'Lock'
                    """).getFirst());
            if (waiting >= n) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the two writers never both waited on the workspace lock");
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
