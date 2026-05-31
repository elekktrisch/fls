package ch.alpenflight.migration.tool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

/**
 * Owns the read-only, forward-only legacy MSSQL connection. The export is a
 * pure read against a production legacy instance, so the connection is
 * hardened on three axes:
 *
 * <ul>
 *   <li>{@code ApplicationIntent=ReadOnly} forced onto the JDBC URL — the
 *       driver routes to a readable secondary on an AG and the export can
 *       never issue a write.</li>
 *   <li>{@code responseBuffering=adaptive} + an explicit fetch size so a
 *       multi-million-row table streams rather than materialising in driver
 *       memory.</li>
 *   <li>{@link Connection#setReadOnly(boolean)} as defence in depth.</li>
 * </ul>
 *
 * <p>Each {@link #openEntityCursor} returns a forward-only, read-only
 * {@link ResultSet}; the caller drains it once and closes it (closing the
 * statement too).
 */
public final class LegacyJdbcReader implements AutoCloseable {

    private static final int FETCH_SIZE = 1000;

    private final Connection connection;

    private LegacyJdbcReader(Connection connection) {
        this.connection = connection;
    }

    public static LegacyJdbcReader open(String jdbcUrl, String user, char[] password) {
        String hardenedUrl = forceReadOnlyIntent(jdbcUrl);
        Properties props = new Properties();
        if (user != null) {
            props.setProperty("user", user);
        }
        if (password != null) {
            props.setProperty("password", new String(password));
        }
        props.setProperty("responseBuffering", "adaptive");
        try {
            Connection connection = DriverManager.getConnection(hardenedUrl, props);
            connection.setReadOnly(true);
            connection.setAutoCommit(true);
            return new LegacyJdbcReader(connection);
        } catch (SQLException e) {
            throw new ExportException(ExitCode.JDBC_CONNECT_FAILED,
                    "Failed to connect to legacy database: " + e.getMessage(), e);
        }
    }

    /**
     * Opens a forward-only, read-only cursor for one entity SELECT. The
     * returned {@link ResultSet}'s {@link ResultSet#getStatement()} is
     * closed when the result set is closed (driver default) — callers use
     * try-with-resources on the result set.
     */
    public ResultSet openEntityCursor(String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        ps.setFetchSize(FETCH_SIZE);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    /**
     * Adds {@code ApplicationIntent=ReadOnly} when absent, overrides a
     * {@code ReadWrite} intent the operator may have pasted. SQL Server URL
     * properties are {@code ;}-delimited {@code key=value} pairs after the
     * {@code jdbc:sqlserver://host} prefix.
     */
    static String forceReadOnlyIntent(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        int intentAt = lower.indexOf("applicationintent=");
        if (intentAt < 0) {
            String separator = jdbcUrl.endsWith(";") ? "" : ";";
            return jdbcUrl + separator + "ApplicationIntent=ReadOnly";
        }
        int valueStart = intentAt + "applicationintent=".length();
        int valueEnd = jdbcUrl.indexOf(';', valueStart);
        if (valueEnd < 0) {
            valueEnd = jdbcUrl.length();
        }
        return jdbcUrl.substring(0, valueStart) + "ReadOnly" + jdbcUrl.substring(valueEnd);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Best-effort close on a read-only connection — nothing to roll back.
        }
    }
}
