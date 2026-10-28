// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.vectis.domain.TimeOrderedId;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The constraints of V4 that a repository test would not reach (VEC-68 AC1): published versions
 * are immutable, a template key is never shadowed, and a column key is a state key, once per
 * board. Every case runs in a transaction it rolls back.
 */
@QuarkusTest
class TemplateSchemaTest {

    @Inject DataSource dataSource;

    private interface Body {
        void run(Statement st) throws SQLException;
    }

    private void inRolledBackTransaction(Body body) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                body.run(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static final String SCRUM_ONE = """
            from workflow_template t where t.id = workflow_template_version.template_id
               and t.tenant_id is null and t.key = 'scrum' and workflow_template_version.version = 1
            """;

    @Test
    void aPublishedVersionCannotBeUpdated() throws SQLException {
        inRolledBackTransaction(st -> {
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate(
                    "update workflow_template_version set resolved = '{}'::jsonb " + SCRUM_ONE));
            assertTrue(e.getMessage().contains("scrum@1 is published and immutable"), e.getMessage());
        });
    }

    @Test
    void aPublishedVersionCannotBeDeleted() throws SQLException {
        inRolledBackTransaction(st -> {
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate(
                    "delete from workflow_template_version using workflow_template t"
                            + " where t.id = workflow_template_version.template_id and t.key = 'kanban'"));
            assertTrue(e.getMessage().contains("kanban@1 is published and immutable"), e.getMessage());
        });
    }

    @Test
    void aTenantTemplateCannotTakeABuiltInKey() throws SQLException {
        inRolledBackTransaction(st -> {
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate(
                    "insert into workflow_template (id, tenant_id, key) values ('%s', '%s', 'scrum')"
                            .formatted(TimeOrderedId.next(), UUID.randomUUID())));
            assertEquals("23505", e.getSQLState());
            assertTrue(e.getMessage().contains("template key \"scrum\" is taken"), e.getMessage());
        });
    }

    @Test
    void aBuiltInCannotTakeATenantKey() throws SQLException {
        inRolledBackTransaction(st -> {
            st.executeUpdate("insert into workflow_template (id, tenant_id, key) values ('%s', '%s', 'acme')"
                    .formatted(TimeOrderedId.next(), UUID.randomUUID()));
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate(
                    "insert into workflow_template (id, tenant_id, key) values ('%s', null, 'acme')"
                            .formatted(TimeOrderedId.next())));
            assertTrue(e.getMessage().contains("template key \"acme\" is taken"), e.getMessage());
        });
    }

    @Test
    void twoTenantsMayUseOneKeyButOneTenantOnlyOnce() throws SQLException {
        UUID tenant = UUID.randomUUID();
        inRolledBackTransaction(st -> {
            st.executeUpdate("insert into workflow_template (id, tenant_id, key) values ('%s', '%s', 'acme')"
                    .formatted(TimeOrderedId.next(), tenant));
            st.executeUpdate("insert into workflow_template (id, tenant_id, key) values ('%s', '%s', 'acme')"
                    .formatted(TimeOrderedId.next(), UUID.randomUUID()));
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate(
                    "insert into workflow_template (id, tenant_id, key) values ('%s', '%s', 'acme')"
                            .formatted(TimeOrderedId.next(), tenant)));
            assertTrue(e.getMessage().contains("workflow_template_owner_key"), e.getMessage());
        });
    }

    @Test
    void aSecondBuiltInWithAnExistingKeyIsRefused() throws SQLException {
        inRolledBackTransaction(st -> {
            SQLException e = assertThrows(SQLException.class, () -> st.executeUpdate(
                    "insert into workflow_template (id, tenant_id, key) values ('%s', null, 'base')"
                            .formatted(TimeOrderedId.next())));
            assertTrue(e.getMessage().contains("workflow_template_owner_key"), e.getMessage());
        });
    }

    @Test
    void aColumnKeyIsAStateKeyOncePerBoard() throws SQLException {
        UUID board = TimeOrderedId.next();
        String column = "insert into board_column (id, board_id, key, name, ordinal) values ('%s', '%s', '%s', 'Column', %d)";
        inRolledBackTransaction(st -> {
            UUID ws = TimeOrderedId.next();
            st.execute("""
                    insert into workspace (id, key, name, template_id, template_version)
                    select '%1$s', 'COLKEYS', 'Probe', id, 1 from workflow_template where tenant_id is null and key = 'scrum';
                    insert into board (id, workspace_id, name) values ('%2$s', '%1$s', 'Probe');
                    """.formatted(ws, board));
            st.executeUpdate(column.formatted(TimeOrderedId.next(), board, "qa", 0));
            st.execute("savepoint s");
            SQLException twice = assertThrows(SQLException.class,
                    () -> st.executeUpdate(column.formatted(TimeOrderedId.next(), board, "qa", 1)));
            assertTrue(twice.getMessage().contains("board_column_board_key_key"), twice.getMessage());
            st.execute("rollback to savepoint s");
            SQLException malformed = assertThrows(SQLException.class,
                    () -> st.executeUpdate(column.formatted(TimeOrderedId.next(), board, "In QA", 1)));
            assertTrue(malformed.getMessage().contains("board_column_key_check"), malformed.getMessage());
        });
    }
}
