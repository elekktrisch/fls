package ch.alpenflight.legacyextract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class MssqlTestContainerLifecycle {

    private static final String IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    static final String SA_PASSWORD = "TestPa$$w0rd_2026";
    static final String LEGACY_DATABASE_NAME = "FLSTest";
    private static final int READINESS_TIMEOUT_SECONDS = 90;
    private static final int READINESS_POLL_INTERVAL_MILLIS = 1500;
    private static final int HOST_PORT_READ_ATTEMPTS = 10;
    private static final int HOST_PORT_RETRY_DELAY_MILLIS = 500;

    private static final MssqlTestContainerLifecycle SHARED_ACROSS_TEST_CLASSES =
            new MssqlTestContainerLifecycle();

    private final String containerName = "alpenflight-extract-test-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;
    private volatile LegacyExtractFixtureSeeder.SeedResult seedResult = null;

    public static MssqlTestContainerLifecycle shared() {
        return SHARED_ACROSS_TEST_CLASSES;
    }

    public synchronized LegacyExtractFixtureSeeder.SeedResult seedLegacyFixtureOnce(Path flsTestRoot)
            throws IOException {
        if (seedResult == null) {
            seedResult = LegacyExtractFixtureSeeder.applyAll(dataSource(), flsTestRoot);
        }
        return seedResult;
    }

    public DataSource dataSource() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(jdbcUrl(), username(), password());
        dataSource.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        return dataSource;
    }

    public synchronized void start() {
        if (started) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopQuietly, "alpenflight-extract-mssql-shutdown"));
        runOrThrow("docker", "pull", IMAGE);
        runOrThrow(
                "docker", "run", "-d",
                "--name", containerName,
                "-e", "ACCEPT_EULA=Y",
                "-e", "MSSQL_SA_PASSWORD=" + SA_PASSWORD,
                "-p", "0:1433",
                IMAGE);
        hostPort = readHostPort();
        started = true;
        try {
            waitForReady();
            createLegacyDatabase();
        } catch (RuntimeException e) {
            started = false;
            throw e;
        }
    }

    private void createLegacyDatabase() {
        Properties props = new Properties();
        props.setProperty("user", username());
        props.setProperty("password", password());
        try (Connection c = DriverManager.getConnection(serverJdbcUrl(), props)) {
            c.createStatement().execute(
                    "IF DB_ID('" + LEGACY_DATABASE_NAME + "') IS NULL"
                            + " CREATE DATABASE [" + LEGACY_DATABASE_NAME + "]");
        } catch (SQLException e) {
            stopQuietly();
            throw new IllegalStateException(
                    "could not create the " + LEGACY_DATABASE_NAME + " database in container "
                            + containerName + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop() {
        if (!started) return;
        stopQuietly();
        started = false;
    }

    private void stopQuietly() {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName).redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {
        }
    }

    public String jdbcUrl() {
        return serverJdbcUrl() + ";databaseName=" + LEGACY_DATABASE_NAME;
    }

    private String serverJdbcUrl() {
        ensureStarted();
        return "jdbc:sqlserver://localhost:" + hostPort + ";encrypt=false;trustServerCertificate=true";
    }

    public String username() {
        return "sa";
    }

    public String password() {
        return SA_PASSWORD;
    }

    public int hostPort() {
        ensureStarted();
        return hostPort;
    }

    public String containerName() {
        return containerName;
    }

    private void ensureStarted() {
        if (!started) throw new IllegalStateException("container not started — call start() first");
    }

    private int readHostPort() {
        for (int attempt = 0; attempt < HOST_PORT_READ_ATTEMPTS; attempt++) {
            try {
                String publishedPortLines = captureOutput("docker", "port", containerName, "1433/tcp");
                for (String line : publishedPortLines.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.contains("[::]")) continue;
                    int colon = trimmed.lastIndexOf(':');
                    if (colon >= 0) {
                        return Integer.parseInt(trimmed.substring(colon + 1).trim());
                    }
                }
            } catch (IOException | InterruptedException portNotPublishedYet) {
            }
            sleepQuietly(HOST_PORT_RETRY_DELAY_MILLIS);
        }
        throw new IllegalStateException("could not determine host port for " + containerName);
    }

    private void waitForReady() {
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_SECONDS * 1000L;
        String url = serverJdbcUrl();
        Properties props = new Properties();
        props.setProperty("user", username());
        props.setProperty("password", password());
        Throwable lastConnectionFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(url, props)) {
                c.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException e) {
                lastConnectionFailure = e;
                sleepQuietly(READINESS_POLL_INTERVAL_MILLIS);
            }
        }
        stopQuietly();
        throw new IllegalStateException(
                "SQL Server in container " + containerName + " not ready within "
                        + READINESS_TIMEOUT_SECONDS + "s: "
                        + (lastConnectionFailure != null ? lastConnectionFailure.getMessage() : "unknown"));
    }

    private static void runOrThrow(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = readAll(p);
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("command failed: " + String.join(" ", cmd) + "\n" + out);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("command failed: " + String.join(" ", cmd), e);
        }
    }

    private static String captureOutput(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = readAll(p);
        p.waitFor();
        return out;
    }

    private static String readAll(Process p) throws IOException {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
