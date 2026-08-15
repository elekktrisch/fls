package ch.alpenflight.server.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

public final class PostgresTestContainerLifecycle {

    private static final String IMAGE = "postgres:17.4-alpine";
    static final String DB_NAME = "alpenflight_test";
    static final String DB_USER = "alpenflight_test";
    static final String DB_PASSWORD = "alpenflight_test_pw";
    private static final int READINESS_TIMEOUT_SECONDS = 120;

    public static final String APP_ROLE_USER = "alpenflight_app";
    public static final String APP_ROLE_PASSWORD = "alpenflight_app";

    private static final long ONE_TEST_JVM_AT_A_TIME_LOCK_KEY = 0x414C50464C545354L;
    private static final int EXTERNAL_LOCK_WAIT_SECONDS = 15;

    private static final String TEST_OWNED_CONTAINER_LABEL = "ch.alpenflight.test=pg";

    private static final int STALE_CONTAINER_MIN_AGE_SECONDS = 60;
    private static final long UNINSPECTABLE_CONTAINER_AGE_SECONDS = Long.MAX_VALUE;

    private final String containerName = "alpenflight-pg-test-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;

    private volatile boolean external = false;
    private volatile String externalJdbcUrl;
    private volatile String externalUser;
    private volatile String externalPassword;
    private volatile Connection externalLockConnection;

    static boolean externalConfigured() {
        return System.getenv("DATASOURCE_URL") != null
                && System.getenv("CI") == null
                && System.getenv("ALPENFLIGHT_TEST_FORCE_DOCKER") == null;
    }

    static boolean localContainerLaunchForbidden(String datasourceUrl, String ci, String forceDocker) {
        if (ci != null) {
            return false;
        }
        boolean external = datasourceUrl != null && forceDocker == null;
        return !external;
    }

    private static void guardAgainstLocalContainerOnDevBox() {
        boolean forbidden = localContainerLaunchForbidden(
                System.getenv("DATASOURCE_URL"),
                System.getenv("CI"),
                System.getenv("ALPENFLIGHT_TEST_FORCE_DOCKER"));
        if (!forbidden) {
            return;
        }
        throw new IllegalStateException(
                """
                Refusing to launch a local Postgres container on the dev box (CI is unset).
                Dev-box tests MUST run against the LAN Postgres in external mode — source \
                ~/.bashrc DATASOURCE_URL/DATASOURCE_USER/DATASOURCE_PASSWORD and run with \
                ALPENFLIGHT_TEST_FORKS=1. Never spin an alpenflight-pg-test-* container locally \
                and do NOT set ALPENFLIGHT_TEST_FORCE_DOCKER to work around this. A CREATEROLE-\
                needing IT (e.g. the S-160 append-only role-split IT) is EXPECTED to fail-loud-skip \
                locally and runs FOR REAL in CI's container mode.""");
    }

    public synchronized void start() {
        if (started) return;
        if (externalConfigured()) {
            startExternal();
            return;
        }
        guardAgainstLocalContainerOnDevBox();
        sweepStaleContainers();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopQuietly, "alpenflight-pg-shutdown"));
        runOrThrow("docker", "pull", IMAGE);
        runOrThrow(
                "docker", "run", "-d",
                "--name", containerName,
                "--label", TEST_OWNED_CONTAINER_LABEL,
                "-e", "POSTGRES_DB=" + DB_NAME,
                "-e", "POSTGRES_USER=" + DB_USER,
                "-e", "POSTGRES_PASSWORD=" + DB_PASSWORD,
                "-p", "0:5432",
                IMAGE);
        hostPort = readHostPort();
        started = true;
        try {
            waitForReady();
        } catch (RuntimeException e) {
            started = false;
            stopQuietly();
            throw e;
        }
    }

    private void startExternal() {
        String testUrl = System.getenv("DATASOURCE_URL");
        String user = System.getenv("DATASOURCE_USER");
        String password = System.getenv("DATASOURCE_PASSWORD");
        if (user == null || password == null) {
            throw new IllegalStateException(
                    "External-PG test mode: DATASOURCE_URL is set but DATASOURCE_USER/DATASOURCE_PASSWORD are not.");
        }

        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        Connection lock;
        try {
            lock = DriverManager.getConnection(testUrl, props);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "External-PG test mode: cannot connect to " + testUrl + " (" + e.getMessage()
                            + "). External mode never falls back to a local container (operator directive).",
                    e);
        }
        try {
            boolean locked = false;
            long deadline = System.currentTimeMillis() + EXTERNAL_LOCK_WAIT_SECONDS * 1000L;
            while (System.currentTimeMillis() < deadline) {
                try (var rs = lock.createStatement()
                        .executeQuery("SELECT pg_try_advisory_lock(" + ONE_TEST_JVM_AT_A_TIME_LOCK_KEY + ")")) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        locked = true;
                        break;
                    }
                }
                sleepQuietly(1000);
            }
            if (!locked) {
                throw new IllegalStateException(
                        "External-PG test mode: another test JVM holds " + DB_NAME + " (advisory lock busy "
                                + EXTERNAL_LOCK_WAIT_SECONDS + "s). Run with ALPENFLIGHT_TEST_FORKS=1 and"
                                + " one gradle test invocation at a time.");
            }
            resetPublicSchemaSoFlywayMigratesFresh(lock);
        } catch (SQLException e) {
            try {
                lock.close();
            } catch (SQLException ignored) {
            }
            throw new IllegalStateException("External-PG test mode: schema reset failed on " + testUrl, e);
        } catch (RuntimeException e) {
            try {
                lock.close();
            } catch (SQLException ignored) {
            }
            throw e;
        }
        externalLockConnection = lock;
        externalJdbcUrl = testUrl;
        externalUser = user;
        externalPassword = password;
        external = true;
        started = true;
    }

    private static void resetPublicSchemaSoFlywayMigratesFresh(Connection conn) throws SQLException {
        conn.createStatement().execute("DROP SCHEMA public CASCADE");
        conn.createStatement().execute("CREATE SCHEMA public");
    }

    public synchronized void stop() {
        if (!started) return;
        stopQuietly();
        started = false;
    }

    private void stopQuietly() {
        if (external) {
            try {
                if (externalLockConnection != null) {
                    externalLockConnection.close();
                }
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
        }
    }

    private static void sweepStaleContainers() {
        try {
            String out = captureOutput(
                    "docker", "ps", "-a",
                    "--filter", "name=alpenflight-pg-test-",
                    "--format", "{{.Names}}\t{{.RunningFor}}");
            long now = System.currentTimeMillis();
            for (String line : out.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                int tab = trimmed.indexOf('\t');
                String name = tab >= 0 ? trimmed.substring(0, tab).trim() : trimmed;
                if (name.isEmpty()) continue;
                long ageSeconds = ageSecondsOf(name, now);
                if (ageSeconds < STALE_CONTAINER_MIN_AGE_SECONDS) {
                    continue;
                }
                removeContainerAndItsAnonymousVolumeQuietly(name);
            }
        } catch (Exception ignored) {
        }
    }

    private static long ageSecondsOf(String name, long nowMillis) {
        try {
            String created = captureOutput("docker", "inspect", "-f", "{{.Created}}", name).trim();
            if (created.isEmpty()) return UNINSPECTABLE_CONTAINER_AGE_SECONDS;
            long createdMillis = java.time.Instant.parse(created).toEpochMilli();
            return (nowMillis - createdMillis) / 1000L;
        } catch (Exception e) {
            return UNINSPECTABLE_CONTAINER_AGE_SECONDS;
        }
    }

    private static void removeContainerAndItsAnonymousVolumeQuietly(String name) {
        try {
            new ProcessBuilder("docker", "rm", "-f", "-v", name)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
        }
    }

    public String jdbcUrl() {
        ensureStarted();
        if (external) {
            return externalJdbcUrl;
        }
        return "jdbc:postgresql://localhost:" + hostPort + "/" + DB_NAME;
    }

    public Connection appRoleConnection() throws SQLException {
        ensureStarted();
        Properties props = new Properties();
        props.setProperty("user", APP_ROLE_USER);
        props.setProperty("password", APP_ROLE_PASSWORD);
        return DriverManager.getConnection(jdbcUrl(), props);
    }

    public String username() {
        return external ? externalUser : DB_USER;
    }

    public String password() {
        return external ? externalPassword : DB_PASSWORD;
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
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                String out = captureOutput("docker", "port", containerName, "5432/tcp");
                for (String line : out.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    if (trimmed.contains("[::]")) continue;
                    int colon = trimmed.lastIndexOf(':');
                    if (colon >= 0) {
                        return Integer.parseInt(trimmed.substring(colon + 1).trim());
                    }
                }
            } catch (IOException | InterruptedException e) {
            }
            sleepQuietly(500);
        }
        throw new IllegalStateException("could not determine host port for " + containerName);
    }

    private void waitForReady() {
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_SECONDS * 1000L;
        Properties props = new Properties();
        props.setProperty("user", DB_USER);
        props.setProperty("password", DB_PASSWORD);
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(jdbcUrl(), props)) {
                c.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                sleepQuietly(1000);
            }
        }
        throw new IllegalStateException(
                "Postgres in container " + containerName + " not ready within "
                        + READINESS_TIMEOUT_SECONDS + "s: "
                        + (last != null ? last.getMessage() : "unknown"));
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
