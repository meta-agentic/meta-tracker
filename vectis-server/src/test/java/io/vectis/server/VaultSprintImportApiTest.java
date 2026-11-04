// SPDX-License-Identifier: Apache-2.0
package io.vectis.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * VEC-40 against PostgreSQL 16: the import carries the vault's sprints and each item's sprint, and
 * {@code /api/v1} serves them. The vault is built here: four sprints, two of them active, and four
 * items, one of them listing two sprints and one naming a sprint the vault lacks.
 */
@QuarkusTest
@TestProfile(ImportTestProfile.class)
@QuarkusTestResource(value = DatabaseResource.class, restrictToAnnotatedClass = true)
class VaultSprintImportApiTest {

    private static final Path SPACE = ImportTestProfile.VAULT.resolve("vec");

    @BeforeEach
    void freshVaultAndNoWorkspace() throws IOException {
        if (Files.exists(ImportTestProfile.VAULT)) {
            try (Stream<Path> all = Files.walk(ImportTestProfile.VAULT)) {
                for (Path path : all.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        for (String dir : List.of("raw", "wiki", "output", "sprints")) {
            Files.createDirectories(SPACE.resolve(dir));
        }
        write("_backlog-meta.yaml", "idPolicy:\n  prefix: VEC\n");
        sprint("VEC-S1", "closed", "start: '2026-10-01'\nend: '2026-10-14'\nclosed: '2026-10-15'\n");
        sprint("VEC-S2", "active", "start: '2026-10-15'\nend: '2026-10-28'\ngoal: Read the vault in Vectis\n");
        sprint("VEC-S3", "active", "start: '2026-10-29'\nend: '2026-11-11'\n");
        sprint("VEC-S4", "future", "");
        item("output", "VEC-1", "DONE", "sprint:\n- VEC-S1\n- VEC-S2\n", 3);
        item("raw", "VEC-2", "TO DO", "sprint: VEC-S2\n", 2);
        item("raw", "VEC-3", "TO DO", "sprint: VEC-S9\n", 5);
        item("raw", "VEC-4", "TO DO", "", 1);
        update("delete from workspace where key = 'VEC'");
    }

    @Test
    void sprintsAreImportedWithTheirStateDatesAndGoalAndOnlyTheLatestActiveStaysActive() {
        post().then().statusCode(200).body("workspace", equalTo("VEC"));

        given().get("/api/v1/workspaces/VEC/sprints").then().statusCode(200)
                .body("key", equalTo(List.of("VEC-S1", "VEC-S2", "VEC-S3", "VEC-S4")))
                .body("name", equalTo(List.of("VEC-S1", "VEC-S2", "VEC-S3", "VEC-S4")))
                .body("state", equalTo(List.of("COMPLETED", "COMPLETED", "ACTIVE", "FUTURE")))
                .body("[0].start", equalTo("2026-10-01")).body("[0].end", equalTo("2026-10-14"))
                .body("[0].closed", equalTo("2026-10-15")).body("[0].goal", nullValue())
                .body("[1].goal", equalTo("Read the vault in Vectis")).body("[2].closed", nullValue())
                .body("[3].start", nullValue());
    }

    @Test
    void anItemTakesTheLatestSprintItListsAndAnUnknownSprintIsAProblemNotAFailure() {
        Response report = post();

        report.then().statusCode(200).body("created", equalTo(4)).body("problemCount", equalTo(2))
                .body("problems", hasItem(containsString("VEC-S2, VEC-S3] are all active; only VEC-S3")))
                .body("problems", hasItem(containsString("sprint 'VEC-S9' is not in the vault")));
        assertEquals(List.of("VEC-1", "VEC-2"), itemKeys("VEC-S2").stream().sorted().toList(), "the list VEC-S1, VEC-S2 puts VEC-1 in VEC-S2");
        given().get("/api/v1/workspaces/VEC/items?sprint=VEC-S1").then().statusCode(200).body("items", equalTo(List.of()));
        given().get("/api/v1/workspaces/VEC/items").then().body("items.key", hasItem("VEC-3"));
        assertEquals(List.of(), itemKeys("VEC-S3"));
    }

    @Test
    void sprintsReportTheirCommittedAndDonePoints() {
        post().then().statusCode(200);

        given().get("/api/v1/workspaces/VEC/sprints").then().statusCode(200)
                .body("[1].committedPoints", equalTo(5.0f)).body("[1].donePoints", equalTo(3.0f))
                .body("[0].committedPoints", equalTo(0.0f)).body("[3].donePoints", equalTo(0.0f));
    }

    @Test
    void importingAgainChangesNothing() {
        post().then().statusCode(200);
        var sprints = given().get("/api/v1/workspaces/VEC/sprints").asString();
        var items = given().get("/api/v1/workspaces/VEC/items").asString();

        post().then().statusCode(200).body("created", equalTo(0)).body("updated", equalTo(0))
                .body("unchanged", equalTo(4));

        assertEquals(sprints, given().get("/api/v1/workspaces/VEC/sprints").asString());
        assertEquals(items, given().get("/api/v1/workspaces/VEC/items").asString(), "no item gained a version");
    }

    @Test
    void anEditedSprintIsUpdatedAndTheActiveSprintMovesOnWithoutTwoBeingActive() {
        post().then().statusCode(200);
        sprint("VEC-S3", "closed", "start: '2026-10-29'\nend: '2026-11-11'\nclosed: '2026-11-12'\n");
        sprint("VEC-S4", "active", "start: '2026-11-12'\ngoal: Next\n");
        item("raw", "VEC-4", "TO DO", "sprint: VEC-S4\n", 1);

        post().then().statusCode(200).body("created", equalTo(0)).body("updated", equalTo(1))
                .body("unchanged", equalTo(3)).body("problemCount", equalTo(2));

        given().get("/api/v1/workspaces/VEC/sprints").then().statusCode(200)
                .body("state", equalTo(List.of("COMPLETED", "COMPLETED", "COMPLETED", "ACTIVE")))
                .body("[2].closed", equalTo("2026-11-12")).body("[3].goal", equalTo("Next"));
        assertEquals(List.of("VEC-4"), itemKeys("VEC-S4"));
    }

    @Test
    void anUnknownWorkspaceOrSprintIsAProblemJson404() {
        post().then().statusCode(200);

        given().get("/api/v1/workspaces/NOPE/sprints").then().statusCode(404)
                .contentType("application/problem+json").body("status", equalTo(404));
        given().get("/api/v1/workspaces/NOPE/items?sprint=VEC-S2").then().statusCode(404)
                .contentType("application/problem+json");
        given().get("/api/v1/workspaces/VEC/items?sprint=VEC-S99").then().statusCode(404)
                .contentType("application/problem+json").body("detail", containsString("VEC-S99"));
    }

    private static List<String> itemKeys(String sprint) {
        return given().get("/api/v1/workspaces/VEC/items?sprint=" + sprint).then().statusCode(200).extract()
                .jsonPath().getList("items.key");
    }

    private static Response post() {
        return given().post("/api/v1/imports/vault");
    }

    private static void sprint(String key, String state, String more) {
        write("sprints/" + key + ".md", "---\nkind: sprint\nspace: vec\nsprintId: " + key + "\nstate: " + state + "\n"
                + more + "---\n\n# " + key + "\n");
    }

    private static void item(String tier, String key, String status, String sprint, int points) {
        write(tier + "/" + key + ".md", "---\nkind: story\nid: " + key + "\ntitle: Item " + key + "\nstatus: " + status
                + "\nspace: vec\nstoryPoints: " + points + "\n" + sprint + "---\n\nBody.\n");
    }

    private static void write(String file, String text) {
        try {
            Files.writeString(SPACE.resolve(file), text);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void update(String statement) {
        Config config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("quarkus.datasource.jdbc.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
             PreparedStatement st = c.prepareStatement(statement)) {
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
