package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

/**
 * The event write path migration applied to a database that already holds data, which the
 * rest of the suite (migrating an empty database at start) cannot show.
 *
 * <p>It runs in a schema of its own: migrate it to the version before, write rows the way the
 * previous schema allowed, then migrate it to the latest and check what the existing rows
 * became. The schema is dropped afterwards; the application's own schema is not touched.
 */
@QuarkusTest
class EventMigrationTest {

    private static final String SCHEMA = "event_migration_probe";

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

    /**
     * A connection whose transaction resolves names in the probe schema. {@code set local}
     * ends with the transaction, so the pooled connection goes back with its search path intact.
     */
    private Connection inProbeSchema() throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("set local search_path to " + SCHEMA);
        }
        return c;
    }

    @Test
    void existingRowsGetVersionOneSeqZeroAndAFreshEpochEach() throws SQLException {
        Flyway before = flyway(MigrationVersion.fromVersion("2"));
        before.clean();
        before.migrate();
        try {
            try (Connection c = inProbeSchema(); Statement st = c.createStatement()) {
                st.execute("""
                        insert into workspace (id, key, name) values
                            ('00000000-0000-7000-8000-000000000001', 'OLD', 'Old'),
                            ('00000000-0000-7000-8000-000000000002', 'OLDER', 'Older');
                        insert into board (id, workspace_id, name) values
                            ('00000000-0000-7000-8000-000000000011', '00000000-0000-7000-8000-000000000001', 'B');
                        insert into board_column (id, board_id, name, ordinal) values
                            ('00000000-0000-7000-8000-000000000021', '00000000-0000-7000-8000-000000000011', 'C', 0);
                        insert into item (id, workspace_id, board_id, column_id, key, title, rank) values
                            ('00000000-0000-7000-8000-000000000031', '00000000-0000-7000-8000-000000000001',
                             '00000000-0000-7000-8000-000000000011', '00000000-0000-7000-8000-000000000021',
                             'OLD-1', 'Existing', 'm');
                        """);
                c.commit();
            }

            flyway(MigrationVersion.LATEST).migrate();

            try (Connection c = inProbeSchema(); Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("select version from item")) {
                    rs.next();
                    assertEquals(1, rs.getLong("version"), "an existing item starts at version 1");
                }
                List<String> epochs = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("select event_seq, stream_epoch from workspace order by key")) {
                    while (rs.next()) {
                        assertEquals(0, rs.getLong("event_seq"), "an existing workspace's stream starts empty");
                        assertNotNull(rs.getString("stream_epoch"));
                        epochs.add(rs.getString("stream_epoch"));
                    }
                }
                assertEquals(2, epochs.size());
                assertNotEquals(epochs.get(0), epochs.get(1), "every existing workspace gets its own epoch");
                try (ResultSet rs = st.executeQuery("select count(*) from workspace_event")) {
                    rs.next();
                    assertEquals(0, rs.getLong(1));
                }
                c.rollback();
            }
        } finally {
            before.clean();
        }
    }
}
