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

/**
 * Drives a Postgres container's lifecycle via {@code docker} CLI directly.
 *
 * <p>Symmetric with {@code MssqlTestContainerLifecycle} in
 * {@code alpenflight/database/extract/} — see that class's JavaDoc for the
 * "why not Testcontainers" rationale (sandbox Docker daemon enforces API
 * ≥1.44, Testcontainers 1.21.x ships docker-java 3.4.x that negotiates 1.32).
 *
 * <p>First consumer is the Flyway bootstrap smoke test in S-009.
 * S-015 generalizes if a second story needs it; until then this lives in
 * the server module's {@code testsupport} package.
 *
 * <p>Container guarantees:
 * <ul>
 *   <li>Image: {@code postgres:17.4-alpine} (pinned minor + variant; ADR 0002 Postgres 17).
 *   <li>Random container name per JVM run.
 *   <li>Random host port — read back from {@code docker port}.
 *   <li>Shutdown hook removes the container even on JVM crash.
 *   <li>Pre-start sweep reaps stale {@code alpenflight-pg-test-*} containers + their
 *       volumes left by a previously SIGKILLed JVM (the shutdown hook never fires on a
 *       {@code timeout}-kill, so each killed run otherwise leaks a container + ~1 GB
 *       volume). Concurrency-safe: only reaps containers older than
 *       {@link #STALE_CONTAINER_MIN_AGE_SECONDS}, so a sibling run's just-started
 *       container is never touched.
 *   <li>Connection-readiness poll with a 120-second cap (raised from 60s — it timed
 *       out under load, forcing CI round-trips instead of local self-verify).
 * </ul>
 */
public final class PostgresTestContainerLifecycle {

    private static final String IMAGE = "postgres:17.4-alpine";
    static final String DB_NAME = "alpenflight_test";
    static final String DB_USER = "alpenflight_test";
    static final String DB_PASSWORD = "alpenflight_test_pw";
    private static final int READINESS_TIMEOUT_SECONDS = 120;

    /**
     * Runtime app login role V54 provisions (S-160): broad DML but INSERT,SELECT-
     * only on {@code t_mutation_audit_event}. The password is V54's
     * {@code ${app_role_password}} placeholder; the test profile resolves it to
     * {@link #APP_ROLE_PASSWORD} (application.yml/-test.yml default, no
     * {@code APP_DB_PASSWORD} env under test). {@link #appRoleConnection()} opens
     * a connection AS this role so the append-only IT can prove the REVOKE bites.
     */
    public static final String APP_ROLE_USER = "alpenflight_app";
    public static final String APP_ROLE_PASSWORD = "alpenflight_app";

    /**
     * Advisory-lock key guarding the shared external test database — one test
     * JVM at a time. Arbitrary constant; must only be unique within our use of
     * the external server.
     */
    private static final long EXTERNAL_LOCK_KEY = 0x414C50464C545354L; // "ALPFLTST"
    private static final int EXTERNAL_LOCK_WAIT_SECONDS = 15;

    /** Marker label so the pre-start sweep + the Stop-hook prune can target our containers precisely. */
    private static final String OWNER_LABEL = "ch.alpenflight.test=pg";

    /**
     * Containers younger than this are assumed to belong to a concurrently-running test JVM
     * (started but not yet ready) and are <strong>not</strong> reaped by the pre-start sweep.
     * Stale leaks from a SIGKILLed run are always older than this by the time the next run starts.
     */
    private static final int STALE_CONTAINER_MIN_AGE_SECONDS = 60;

    private final String containerName = "alpenflight-pg-test-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;

    // External-PG mode state (see externalConfigured()).
    private volatile boolean external = false;
    private volatile String externalJdbcUrl;
    private volatile String externalUser;
    private volatile String externalPassword;
    private volatile Connection externalLockConnection;

    /**
     * External-PG mode: when {@code DATASOURCE_URL} is set (dev boxes that must
     * NOT start a local Postgres — operator directive), tests run against that
     * database directly — the shared dev DB, no separate test database
     * (operator decision). CI never takes this path (pinned PG 17 container
     * stays authoritative); {@code ALPENFLIGHT_TEST_FORCE_DOCKER=1} restores
     * the container path on a dev box.
     *
     * <p>Isolation: the schema is dropped + recreated at JVM start (Flyway then
     * migrates fresh — immune to cross-branch migration drift), and a
     * session-held advisory lock serialises test JVMs; run with
     * {@code ALPENFLIGHT_TEST_FORKS=1}. Failures in this mode are LOUD —
     * external is explicit intent, so we never skip-silently nor fall back to
     * a local container.
     */
    static boolean externalConfigured() {
        return System.getenv("DATASOURCE_URL") != null
                && System.getenv("CI") == null
                && System.getenv("ALPENFLIGHT_TEST_FORCE_DOCKER") == null;
    }

    public synchronized void start() {
        if (started) return;
        if (externalConfigured()) {
            startExternal();
            return;
        }
        sweepStaleContainers();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopQuietly, "alpenflight-pg-shutdown"));
        runOrThrow("docker", "pull", IMAGE);
        runOrThrow(
                "docker", "run", "-d",
                "--name", containerName,
                "--label", OWNER_LABEL,
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

    /**
     * Connects to the {@code DATASOURCE_URL} database itself (operator decision:
     * the shared dev DB, no separate test database), serialises against sibling
     * test JVMs via a session advisory lock, then resets the schema so Flyway
     * migrates fresh. Consequence: every test run RESETS the dev database — a
     * fast-loop dev backend running against the same DB must be restarted after
     * a test run (and should not serve traffic during one).
     */
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
                        .executeQuery("SELECT pg_try_advisory_lock(" + EXTERNAL_LOCK_KEY + ")")) {
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
            // Fresh start every JVM: immune to cross-branch Flyway drift; the
            // post-run state stays inspectable until the NEXT run (ADR 0021).
            lock.createStatement().execute("DROP SCHEMA public CASCADE");
            lock.createStatement().execute("CREATE SCHEMA public");
        } catch (SQLException e) {
            try {
                lock.close();
            } catch (SQLException ignored) {
                // best-effort
            }
            throw new IllegalStateException("External-PG test mode: schema reset failed on " + testUrl, e);
        } catch (RuntimeException e) {
            try {
                lock.close();
            } catch (SQLException ignored) {
                // best-effort
            }
            throw e;
        }
        // Hold the lock connection for the JVM's lifetime; the advisory lock is
        // session-scoped and releases when this connection dies with the JVM.
        externalLockConnection = lock;
        externalJdbcUrl = testUrl;
        externalUser = user;
        externalPassword = password;
        external = true;
        started = true;
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
                // best-effort: the advisory lock dies with the session anyway
            }
            return;
        }
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    /**
     * Reap {@code alpenflight-pg-test-*} containers (and the volumes they own) left behind by a
     * previously SIGKILLed JVM, before starting a fresh one. The normal teardown is a JVM shutdown
     * hook that never fires when a worker's {@code timeout NNN ./gradlew test} sends SIGKILL, so each
     * killed run otherwise leaks a container + its ~1 GB volume and slowly fills the dev box.
     *
     * <p>Concurrency-safe: only containers older than {@link #STALE_CONTAINER_MIN_AGE_SECONDS} are
     * removed, so a sibling test JVM's container that is still booting (younger than the guard) is
     * never killed. {@code docker rm -f -v} removes the container's anonymous volume in the same call.
     *
     * <p>Best-effort: any failure (no Docker, permission, race with another sweep) is swallowed — the
     * sweep must never break test startup.
     */
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
                    // Too young — likely a concurrently-booting sibling run; leave it alone.
                    continue;
                }
                removeQuietly(name);
            }
        } catch (Exception ignored) {
            // never let the sweep break startup
        }
    }

    /**
     * Age of a container in seconds via {@code docker inspect} of its {@code .Created} timestamp,
     * which is precise (the {@code docker ps --format} relative-time strings are not parseable). On
     * any failure returns {@link Long#MAX_VALUE} so an un-inspectable leftover is treated as stale
     * and reaped (it is not a live sibling — those inspect fine).
     */
    private static long ageSecondsOf(String name, long nowMillis) {
        try {
            String created = captureOutput("docker", "inspect", "-f", "{{.Created}}", name).trim();
            if (created.isEmpty()) return Long.MAX_VALUE;
            long createdMillis = java.time.Instant.parse(created).toEpochMilli();
            return (nowMillis - createdMillis) / 1000L;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static void removeQuietly(String name) {
        try {
            // -v removes the container's anonymous volume in the same call.
            new ProcessBuilder("docker", "rm", "-f", "-v", name)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    public String jdbcUrl() {
        ensureStarted();
        if (external) {
            return externalJdbcUrl;
        }
        return "jdbc:postgresql://localhost:" + hostPort + "/" + DB_NAME;
    }

    /**
     * Open a JDBC connection AS the {@link #APP_ROLE_USER} runtime role (S-160)
     * against the SAME database the migrator ran Flyway on — so V54 has already
     * created the role there. The caller closes it. Throws if the role cannot
     * log in (e.g. external-PG mode where the shared cluster never provisioned
     * it): the append-only IT translates that into a fail-loud skip, but CI's
     * container path always has the role and connects.
     */
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
                // retry
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
