package ch.alpenflight.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.abort;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AppendOnlyAuditRoleIT extends PostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AppendOnlyAuditRoleIT.class);

    private static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired JdbcTemplate migratorJdbc;

    @Test
    void app_role_can_read_and_append_but_never_update_or_delete_audit_rows() throws SQLException {
        UUID seededId = seedAuditRowAsOwner();

        try (Connection appRole = openAppRoleOrSkip()) {
            assertSelectSucceeds(appRole, seededId);
            assertInsertSucceeds(appRole);
            assertUpdateDeniedWith42501(appRole, seededId);
            assertDeleteDeniedWith42501(appRole, seededId);
        }
    }

    private UUID seedAuditRowAsOwner() {
        UUID id = UUID.randomUUID();
        migratorJdbc.update(
                "INSERT INTO t_mutation_audit_event (id, action, target_entity_type) VALUES (?::uuid, ?, ?)",
                id.toString(), "CREATE", "AppendOnlyProbe");
        return id;
    }

    private Connection openAppRoleOrSkip() {
        PostgresTestContainerLifecycle pg = SharedPostgresContainer.INSTANCE;
        try {
            return pg.appRoleConnection();
        } catch (SQLException e) {
            log.warn("[S-160] Append-only audit IT SKIPPED: could not log in as the '{}' role against {} ({}). "
                            + "This is the fail-loud external-PG fallback — the shared cluster may not have "
                            + "provisioned the second role. CI's Testcontainer provisions it via V54 and runs "
                            + "this assertion FOR REAL.",
                    PostgresTestContainerLifecycle.APP_ROLE_USER, pg.jdbcUrl(), e.getMessage());
            return abort("[S-160] app role '" + PostgresTestContainerLifecycle.APP_ROLE_USER
                    + "' not connectable — fail-loud skip (external-PG fallback; runs for real in CI).");
        }
    }

    private void assertSelectSucceeds(Connection appRole, UUID seededId) throws SQLException {
        try (Statement st = appRole.createStatement();
                var rs = st.executeQuery(
                        "SELECT count(*) FROM t_mutation_audit_event WHERE id = '" + seededId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("app role can SELECT the seeded audit row").isEqualTo(1);
        }
    }

    private void assertInsertSucceeds(Connection appRole) throws SQLException {
        UUID appendedId = UUID.randomUUID();
        try (Statement st = appRole.createStatement()) {
            int inserted = st.executeUpdate(
                    "INSERT INTO t_mutation_audit_event (id, action, target_entity_type) VALUES ('"
                            + appendedId + "', 'CREATE', 'AppendOnlyProbe')");
            assertThat(inserted).as("app role can INSERT (append) an audit row").isEqualTo(1);
        }
    }

    private void assertUpdateDeniedWith42501(Connection appRole, UUID seededId) {
        assertThatThrownBy(() -> {
            try (Statement st = appRole.createStatement()) {
                st.executeUpdate("UPDATE t_mutation_audit_event SET failure_reason = 'tampered' WHERE id = '"
                        + seededId + "'");
            }
        })
                .as("UPDATE on the append-only audit table is permission-denied for the app role")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQLSTATE_INSUFFICIENT_PRIVILEGE);
    }

    private void assertDeleteDeniedWith42501(Connection appRole, UUID seededId) {
        assertThatThrownBy(() -> {
            try (Statement st = appRole.createStatement()) {
                st.executeUpdate("DELETE FROM t_mutation_audit_event WHERE id = '" + seededId + "'");
            }
        })
                .as("DELETE on the append-only audit table is permission-denied for the app role")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(SQLSTATE_INSUFFICIENT_PRIVILEGE);
    }
}
