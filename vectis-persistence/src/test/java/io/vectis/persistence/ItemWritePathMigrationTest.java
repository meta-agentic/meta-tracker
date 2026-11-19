// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V7 applied to a database that already holds workspaces: a workspace the vault import wrote into
 * becomes bound to the vault, and each workspace's key counter starts after the highest number among
 * its own {@code <key>-<n>} items. Like {@link TemplateMigrationTest}, it runs in a schema of its
 * own, seeded the way V3 allowed and migrated to the latest.
 */
@QuarkusTest
class ItemWritePathMigrationTest {

    private static final String SCHEMA = "item_write_path_migration_probe";

    private static final String IMPORTED = "00000000-0000-7000-8000-000000000001";
    private static final String TOUCHED = "00000000-0000-7000-8000-000000000002";
    private static final String QUIET = "00000000-0000-7000-8000-000000000003";
    private static final String BOARD = "00000000-0000-7000-8000-000000000011";
    private static final String COLUMN = "00000000-0000-7000-8000-000000000021";

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

    /**
     * Three V3 workspaces: one the import wrote into, one another writer wrote into, one with no
     * event; the first holds items numbered 3 and 12 under its key, an item under another key that
     * begins with it, and an item whose suffix is not a number.
     */
    @BeforeEach
    void seedV3AndMigrate() throws SQLException {
        Flyway v3 = flyway(MigrationVersion.fromVersion("3"));
        v3.clean();
        v3.migrate();
        try (Connection c = inProbeSchema(); Statement st = c.createStatement()) {
            st.execute("""
                    insert into workspace (id, key, name, event_seq) values
                        ('%1$s', 'OLD', 'Imported', 1),
                        ('%2$s', 'MINE', 'Touched', 1),
                        ('%3$s', 'QUIET', 'Quiet', 0);
                    insert into board (id, workspace_id, name) values ('%4$s', '%1$s', 'B');
                    insert into board_column (id, board_id, name, ordinal) values ('%5$s', '%4$s', 'Backlog', 0);
                    insert into item (id, workspace_id, board_id, column_id, key, title, rank) values
                        ('00000000-0000-7000-8000-000000000031', '%1$s', '%4$s', '%5$s', 'OLD-3', 'Three', 'a'),
                        ('00000000-0000-7000-8000-000000000032', '%1$s', '%4$s', '%5$s', 'OLD-12', 'Twelve', 'b'),
                        ('00000000-0000-7000-8000-000000000033', '%1$s', '%4$s', '%5$s', 'OLDER-99', 'Other key', 'c'),
                        ('00000000-0000-7000-8000-000000000034', '%1$s', '%4$s', '%5$s', 'OLD-x', 'Not a number', 'd');
                    insert into workspace_event (workspace_id, seq, type, payload) values
                        ('%1$s', 1, 'resync', '{"type":"resync","workspaceId":"%1$s","seq":1,"at":"2026-11-01T00:00:00.000000Z","origin":"vault-import","reason":"bulk"}'),
                        ('%2$s', 1, 'resync', '{"type":"resync","workspaceId":"%2$s","seq":1,"at":"2026-11-01T00:00:00.000000Z","origin":"tab-1","reason":"bulk"}');
                    """.formatted(IMPORTED, TOUCHED, QUIET, BOARD, COLUMN));
            c.commit();
        }
        flyway(MigrationVersion.LATEST).migrate();
    }

    @AfterEach
    void dropProbe() {
        flyway(MigrationVersion.LATEST).clean();
    }

    private List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = inProbeSchema(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            c.rollback();
        }
        return out;
    }

    @Test
    void onlyTheWorkspaceTheImportWroteIntoIsBoundToTheVault() throws SQLException {
        assertEquals(Arrays.asList("vault", null, null), column("select source from workspace order by id"));
    }

    @Test
    void theCounterStartsAfterTheHighestNumberOfTheWorkspacesOwnKeysAndAtZeroWithout() throws SQLException {
        assertEquals(List.of("12", "0", "0"), column("select item_seq::text from workspace order by id"));
    }
}
