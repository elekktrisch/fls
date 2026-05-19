package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Shared assertion helpers for migration-shape tests (those that introspect
 * {@code information_schema} + {@code pg_catalog} against the live Postgres
 * test container).
 *
 * <p>Owns the absence-check pre-conditions: tests that assert "table X does
 * NOT have column Y" or "table X has exactly N rows of shape Z" must first
 * confirm the table exists, otherwise the absence check trivially passes
 * when the migration is missing — a silent false-pass.
 *
 * <p>Schema-introspection helpers ({@code checkConstraintDefs},
 * {@code indexDefs}, {@code columnComment}, {@code assertFkDeleteRule},
 * {@code assertColumnNotNull}, {@code assertColumnNullable}) live here so
 * each migration story's test class doesn't re-implement them inline. New
 * helpers added when ≥ 2 migration tests need them.
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

    /** All CHECK-constraint definitions on {@code public.<table>} as raw `pg_get_constraintdef` strings. */
    public static List<String> checkConstraintDefs(DataSource ds, String table) throws SQLException {
        try (Connection conn = ds.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conrelid = (quote_ident(?))::regclass AND contype = 'c'")) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> defs = new ArrayList<>();
                while (rs.next()) defs.add(rs.getString(1));
                return defs;
            }
        }
    }

    /** All index-definition DDL strings on {@code public.<table>} via {@code pg_indexes.indexdef}. */
    public static List<String> indexDefs(DataSource ds, String table) throws SQLException {
        try (Connection conn = ds.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename=?")) {
            stmt.setString(1, table);
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> defs = new ArrayList<>();
                while (rs.next()) defs.add(rs.getString(1));
                return defs;
            }
        }
    }

    /** {@code COMMENT ON COLUMN public.<table>.<column>} via {@code col_description}, or {@code null} if absent. */
    public static String columnComment(DataSource ds, String table, String column) throws SQLException {
        try (Connection conn = ds.getConnection();
                var stmt = conn.prepareStatement(
                        "SELECT col_description((quote_ident(?))::regclass, "
                                + "(SELECT attnum FROM pg_attribute "
                                + " WHERE attrelid = (quote_ident(?))::regclass AND attname = ?))")) {
            stmt.setString(1, table);
            stmt.setString(2, table);
            stmt.setString(3, column);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Asserts {@code <table>.<column>} exists, has {@code data_type}, and is NOT NULL. */
    public static void assertColumnNotNull(Connection conn, String table, String column, String dataType)
            throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("%s.%s must exist", table, column).isTrue();
                assertThat(rs.getString("data_type"))
                        .as("%s.%s type", table, column).isEqualTo(dataType);
                assertThat(rs.getString("is_nullable"))
                        .as("%s.%s NULL?", table, column).isEqualTo("NO");
            }
        }
    }

    /** Asserts {@code <table>.<column>} exists, has {@code data_type}, and IS nullable. */
    public static void assertColumnNullable(Connection conn, String table, String column, String dataType)
            throws SQLException {
        try (var stmt = conn.prepareStatement(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name=? AND column_name=?")) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("%s.%s must exist", table, column).isTrue();
                assertThat(rs.getString("data_type"))
                        .as("%s.%s type", table, column).isEqualTo(dataType);
                assertThat(rs.getString("is_nullable"))
                        .as("%s.%s NULL?", table, column).isEqualTo("YES");
            }
        }
    }

    /**
     * Asserts that {@code public.<table>.<column>} carries a FK whose {@code ON DELETE}
     * rule matches {@code expectedRule} (one of: {@code NO ACTION}, {@code RESTRICT},
     * {@code CASCADE}, {@code SET NULL}, {@code SET DEFAULT}). Uses {@code pg_constraint}
     * (not {@code information_schema}) because {@code information_schema}'s referential-
     * constraint joins are brittle across Postgres versions when position-in-unique-
     * constraint behaviour differs.
     */
    public static void assertFkDeleteRule(DataSource ds, String table, String column, String expectedRule)
            throws SQLException {
        try (Connection conn = ds.getConnection();
                var stmt = conn.prepareStatement("""
                        SELECT
                            CASE c.confdeltype
                                WHEN 'a' THEN 'NO ACTION'
                                WHEN 'r' THEN 'RESTRICT'
                                WHEN 'c' THEN 'CASCADE'
                                WHEN 'n' THEN 'SET NULL'
                                WHEN 'd' THEN 'SET DEFAULT'
                            END AS delete_rule
                        FROM pg_constraint c
                        JOIN pg_class t       ON t.oid = c.conrelid
                        JOIN pg_namespace ns  ON ns.oid = t.relnamespace
                        JOIN pg_attribute a   ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                        WHERE c.contype = 'f'
                          AND ns.nspname = 'public'
                          AND t.relname = ?
                          AND a.attname = ?
                        """)) {
            stmt.setString(1, table);
            stmt.setString(2, column);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next())
                        .as("FK on %s.%s must exist", table, column).isTrue();
                assertThat(rs.getString(1))
                        .as("FK %s.%s ON DELETE rule", table, column)
                        .isEqualTo(expectedRule);
            }
        }
    }
}
