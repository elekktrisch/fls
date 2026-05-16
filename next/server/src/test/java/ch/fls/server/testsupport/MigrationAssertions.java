package ch.fls.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Shared assertion helpers for migration-shape tests (those that introspect
 * {@code information_schema} against the live Postgres test container).
 *
 * <p>Owns the absence-check pre-conditions: tests that assert "table X does
 * NOT have column Y" or "table X has exactly N rows of shape Z" must first
 * confirm the table exists, otherwise the absence check trivially passes
 * when the migration is missing — a silent false-pass.
 */
public final class MigrationAssertions {

    private MigrationAssertions() {}

    /**
     * Asserts that {@code public.<tableName>} exists in the Postgres schema.
     * Used as a precondition before absence-check assertions so an
     * accidentally-empty migration doesn't silently pass tests asserting
     * "table X must NOT have column Y."
     */
    public static void assertTableExists(Connection conn, String tableName) throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?")) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next())
                        .as("precondition: table public.%s must exist before column-shape checks", tableName)
                        .isTrue();
            }
        }
    }
}
