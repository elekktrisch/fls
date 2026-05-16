package ch.fls.server.testsupport;

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
 * {@code next/database/extract/} — see that class's JavaDoc for the
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
 *   <li>Connection-readiness poll with a 60-second cap.
 * </ul>
 */
public final class PostgresTestContainerLifecycle {

    private static final String IMAGE = "postgres:17.4-alpine";
    static final String DB_NAME = "fls_test";
    static final String DB_USER = "fls_test";
    static final String DB_PASSWORD = "fls_test_pw";
    private static final int READINESS_TIMEOUT_SECONDS = 60;

    private final String containerName = "fls-pg-test-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;

    public synchronized void start() {
        if (started) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopQuietly, "fls-pg-shutdown"));
        runOrThrow("docker", "pull", IMAGE);
        runOrThrow(
                "docker", "run", "-d",
                "--name", containerName,
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

    public synchronized void stop() {
        if (!started) return;
        stopQuietly();
        started = false;
    }

    private void stopQuietly() {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    public String jdbcUrl() {
        ensureStarted();
        return "jdbc:postgresql://localhost:" + hostPort + "/" + DB_NAME;
    }

    public String username() {
        return DB_USER;
    }

    public String password() {
        return DB_PASSWORD;
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
