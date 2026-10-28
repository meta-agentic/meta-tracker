// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.template.TemplateJson;
import io.vectis.domain.template.TemplateResolver;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The template hierarchy migrations (V4, V4.1, V4.2) applied to a database that already holds
 * workspaces (VEC-68 AC2), and the built-in seeding they run (AC3).
 *
 * <p>Like {@link EventMigrationTest}, each case runs in a schema of its own: migrate it to V3,
 * write rows the way V3 allowed, migrate it to the latest and check what the rows became. The
 * application's own schema, migrated from empty at start, is the from-empty case.
 */
@QuarkusTest
class TemplateMigrationTest {

    private static final String SCHEMA = "template_migration_probe";

    private static final String WS = "00000000-0000-7000-8000-0000000000";
    private static final String BOARD = "00000000-0000-7000-8000-0000000001";
    private static final String COL = "00000000-0000-7000-8000-0000000002";

    private static final String BUILT_IN_VERSIONS = """
            select count(*) from workflow_template_version v
              join workflow_template t on t.id = v.template_id where t.tenant_id is null
            """;

    @Inject DataSource dataSource;

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private Connection inProbeSchema() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("set local search_path to " + SCHEMA);
        }
        return c;
    }

    private Connection inAppSchema() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        return c;
    }

    /** A V3 database holding {@code sql}'s rows. */
    private void seedV3(String sql) throws SQLException {
        Flyway v3 = flyway(MigrationVersion.fromVersion("3"));
        v3.clean();
        v3.migrate();
        try (Connection c = inProbeSchema(); Statement st = c.createStatement()) {
            st.execute(sql);
            c.commit();
        }
    }

    @AfterEach
    void dropProbe() {
        flyway(MigrationVersion.LATEST).clean();
    }

    private static List<List<String>> rows(Connection c, String sql) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getString(i));
                }
                out.add(row);
            }
        }
        return out;
    }

    @Test
    void fromEmptyTheFourBuiltInsArePublishedWithTheirResolution() throws SQLException {
        try (Connection c = inAppSchema()) {
            assertEquals(List.of(
                            Arrays.asList("base", null, null, "1", "{\"kind\": \"authored\"}", "manual"),
                            List.of("hybrid", "scrum", "1", "1", "{\"kind\": \"authored\"}", "manual"),
                            List.of("kanban", "base", "1", "1", "{\"kind\": \"authored\"}", "manual"),
                            List.of("scrum", "base", "1", "1", "{\"kind\": \"authored\"}", "manual")),
                    rows(c, """
                            select t.key, p.key, v.parent_version::text, v.resolver_version::text, v.cause::text, t.follow
                              from workflow_template t
                              join workflow_template_version v on v.template_id = t.id
                              left join workflow_template p on p.id = v.parent_id
                             where t.tenant_id is null
                             order by t.key, v.version
                            """));
            for (String key : List.of("base", "scrum", "kanban", "hybrid")) {
                List<List<String>> stored = rows(c, "select v.document::text, v.resolved::text from workflow_template t"
                        + " join workflow_template_version v on v.template_id = t.id where t.tenant_id is null and t.key = '"
                        + key + "' and v.version = 1");
                assertEquals(TemplateJson.parseObject(BuiltInTemplates.file(key + ".v1")),
                        TemplateJson.parseObject(stored.getFirst().get(0)), key + "@1 document");
                assertEquals(resolve(key), TemplateJson.parseObject(stored.getFirst().get(1)), key + "@1 resolved");
            }
            c.rollback();
        }
    }

    private static Map<String, Object> resolve(String key) {
        List<String> chain = switch (key) {
            case "base" -> List.of("base.v1");
            case "hybrid" -> List.of("base.v1", "scrum.v1", "hybrid.v1");
            default -> List.of("base.v1", key + ".v1");
        };
        return TemplateResolver.resolve(chain.stream().map(f -> TemplateJson.parseObject(BuiltInTemplates.file(f))).toList(),
                null).effective();
    }

    @Test
    void seedingAgainInsertsNothing() throws SQLException {
        try (Connection c = inAppSchema()) {
            String before = rows(c, BUILT_IN_VERSIONS).getFirst().getFirst();
            assertEquals(0, BuiltInTemplates.seed(c));
            assertEquals(0, BuiltInTemplates.seed(c));
            assertEquals(before, rows(c, BUILT_IN_VERSIONS).getFirst().getFirst());
            assertEquals("4", before);
            c.rollback();
        }
    }

    @Test
    void aPublishedBuiltInWhoseFileChangedFailsTheSeedingAndWritesNothing() throws SQLException {
        try (Connection c = inAppSchema()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> BuiltInTemplates.seed(c,
                    name -> name.equals("scrum.v1")
                            ? BuiltInTemplates.file(name).replace("\"Scrum\"", "\"Scrum, renamed\"")
                            : BuiltInTemplates.file(name)));
            assertTrue(e.getMessage().contains("scrum@1") && e.getMessage().contains("immutable"), e.getMessage());
            assertEquals("4", rows(c, BUILT_IN_VERSIONS).getFirst().getFirst());
            c.rollback();
        }
    }

    @Test
    void existingWorkspacesArePinnedToScrumOneAndTheirColumnsKeyedByName() throws SQLException {
        seedV3("""
                insert into workspace (id, key, name) values
                    ('%1$s01', 'OLD', 'Old'), ('%1$s02', 'OLDER', 'Older');
                insert into board (id, workspace_id, name) values
                    ('%2$s11', '%1$s01', 'Delivery'), ('%2$s12', '%1$s01', 'Second'), ('%2$s13', '%1$s02', 'Delivery');
                insert into board_column (id, board_id, name, ordinal) values
                    ('%3$s21', '%2$s11', 'Backlog', 0), ('%3$s22', '%2$s11', 'In Progress', 1),
                    ('%3$s23', '%2$s11', 'Done', 2), ('%3$s24', '%2$s11', 'No Go', 3),
                    ('%3$s25', '%2$s12', 'To Do', 0), ('%3$s26', '%2$s12', 'In Review', 1),
                    ('%3$s27', '%2$s13', 'Done', 0);
                insert into item (id, workspace_id, board_id, column_id, key, title, rank) values
                    ('%3$s31', '%1$s01', '%2$s11', '%3$s24', 'OLD-1', 'Dropped', 'm');
                """.formatted(WS, BOARD, COL));

        flyway(MigrationVersion.LATEST).migrate();

        try (Connection c = inProbeSchema()) {
            assertEquals(List.of(
                            List.of("OLD", "scrum", "1", "{}", "0", "manual"),
                            List.of("OLDER", "scrum", "1", "{}", "0", "manual")),
                    rows(c, """
                            select w.key, t.key, w.template_version::text, w.config_delta::text,
                                   w.config_revision::text, w.follow
                              from workspace w join workflow_template t on t.id = w.template_id
                             order by w.key
                            """));
            assertEquals(List.of(
                            List.of("Delivery", "Backlog", "backlog", "t"),
                            List.of("Delivery", "In Progress", "in-progress", "t"),
                            List.of("Delivery", "Done", "done", "t"),
                            List.of("Delivery", "No Go", "no-go", "f"),
                            List.of("Second", "To Do", "todo", "t"),
                            List.of("Second", "In Review", "in-review", "t"),
                            List.of("Delivery", "Done", "done", "t")),
                    rows(c, """
                            select b.name, c.name, c.key, c.on_board::text
                              from board_column c join board b on b.id = c.board_id
                             order by b.id, c.ordinal
                            """).stream().map(r -> List.of(r.get(0), r.get(1), r.get(2), r.get(3).substring(0, 1))).toList());
            assertEquals(List.of(List.of("OLD-1", COL + "24")), rows(c, "select key, column_id::text from item"),
                    "items stay where they were");
            c.rollback();
        }
    }

    @Test
    void aColumnThatMapsToNoStateFailsTheMigrationNamingItsWorkspaceAndColumn() throws SQLException {
        seedV3("""
                insert into workspace (id, key, name) values ('%1$s01', 'OLD', 'Old'), ('%1$s02', 'ODD', 'Odd');
                insert into board (id, workspace_id, name) values ('%2$s11', '%1$s01', 'B'), ('%2$s12', '%1$s02', 'B');
                insert into board_column (id, board_id, name, ordinal) values
                    ('%3$s21', '%2$s11', 'Backlog', 0), ('%3$s22', '%2$s12', 'Ready for QA', 0),
                    ('%3$s23', '%2$s12', 'done', 1);
                """.formatted(WS, BOARD, COL));

        FlywayException e = assertThrows(FlywayException.class, () -> flyway(MigrationVersion.LATEST).migrate());

        assertTrue(e.getMessage().contains("workspace ODD: column \"Ready for QA\"")
                && e.getMessage().contains("workspace ODD: column \"done\"")
                && !e.getMessage().contains("workspace OLD"), e.getMessage());
        try (Connection c = inProbeSchema()) {
            assertEquals(List.of(List.of("2")), rows(c, "select count(*) from workspace where template_id is null"),
                    "the failed step changed nothing");
            c.rollback();
        }
    }

    @Test
    void twoColumnsOfOneBoardWithTheSameNameFailTheMigration() throws SQLException {
        seedV3("""
                insert into workspace (id, key, name) values ('%1$s01', 'TWICE', 'Twice');
                insert into board (id, workspace_id, name) values ('%2$s11', '%1$s01', 'B');
                insert into board_column (id, board_id, name, ordinal) values
                    ('%3$s21', '%2$s11', 'Done', 0), ('%3$s22', '%2$s11', 'Done', 1);
                """.formatted(WS, BOARD, COL));

        FlywayException e = assertThrows(FlywayException.class, () -> flyway(MigrationVersion.LATEST).migrate());

        assertTrue(e.getMessage().contains("workspace TWICE: column \"Done\" appears 2 times"), e.getMessage());
    }
}
