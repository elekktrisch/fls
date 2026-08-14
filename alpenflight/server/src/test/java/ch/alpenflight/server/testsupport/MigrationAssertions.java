package ch.alpenflight.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

public final class MigrationAssertions {

    private MigrationAssertions() {}

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
