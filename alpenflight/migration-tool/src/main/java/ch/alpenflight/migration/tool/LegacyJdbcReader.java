package ch.alpenflight.migration.tool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

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

    public ResultSet openEntityCursor(String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(
                sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        ps.setFetchSize(FETCH_SIZE);
        ps.closeOnCompletion();
        return ps.executeQuery();
    }

    static String forceReadOnlyIntent(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (countOccurrences(lower, "applicationintent=") > 1) {
            throw new ExportException(ExitCode.USAGE,
                    "JDBC URL carries more than one ApplicationIntent parameter; "
                            + "remove the duplicates so ReadOnly cannot be overridden.");
        }
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

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
