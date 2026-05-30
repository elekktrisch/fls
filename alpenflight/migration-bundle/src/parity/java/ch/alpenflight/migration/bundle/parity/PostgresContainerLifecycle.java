package ch.alpenflight.migration.bundle.parity;

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
 * Drives a Postgres container's lifecycle via {@code docker} CLI — symmetric
 * with {@link MssqlContainerLifecycle} and with the {@code alpenflight/server}
 * test-support's {@code PostgresTestContainerLifecycle}. Same reason to
 * bypass Testcontainers: the sandbox enforces Docker REST API ≥ 1.44 and
 * the bundled docker-java negotiates 1.32.
 */
public final class PostgresContainerLifecycle {

    private static final String IMAGE = "postgres:17-alpine";
    private static final String DB_NAME = "alpenflight";
    private static final String DB_USER = "alpenflight";
    private static final String DB_PASSWORD = "alpenflight";
    private static final int READINESS_TIMEOUT_SECONDS = 60;

    private final String containerName =
            "alpenflight-parity-pg-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;

    public synchronized void start() {
        if (started) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::stopQuietly, "alpenflight-parity-pg-shutdown"));
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
        } catch (RuntimeException failure) {
            started = false;
            stopQuietly();
            throw failure;
        }
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
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
            // Best-effort cleanup.
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

    private void ensureStarted() {
        if (!started) {
            throw new IllegalStateException(
                    "Postgres container not started — call start() first");
        }
    }

    private int readHostPort() {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                String output = captureOutput("docker", "port", containerName, "5432/tcp");
                for (String line : output.split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.contains("[::]")) {
                        continue;
                    }
                    int colon = trimmed.lastIndexOf(':');
                    if (colon >= 0) {
                        return Integer.parseInt(trimmed.substring(colon + 1).trim());
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                // retry
            }
            sleepQuietly(500);
        }
        throw new IllegalStateException(
                "Could not determine host port for container " + containerName);
    }

    private void waitForReady() {
        long deadline = System.currentTimeMillis() + READINESS_TIMEOUT_SECONDS * 1000L;
        Properties properties = new Properties();
        properties.setProperty("user", username());
        properties.setProperty("password", password());
        Throwable lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl(), properties)) {
                connection.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException retry) {
                lastFailure = retry;
                sleepQuietly(1000);
            }
        }
        throw new IllegalStateException(
                "Postgres in container " + containerName + " not ready within "
                        + READINESS_TIMEOUT_SECONDS + " s: "
                        + (lastFailure == null ? "unknown" : lastFailure.getMessage()));
    }

    private static void runOrThrow(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readAll(process);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "command failed: " + String.join(" ", command) + "\n" + output);
            }
        } catch (IOException | InterruptedException failure) {
            throw new IllegalStateException(
                    "command failed: " + String.join(" ", command), failure);
        }
    }

    private static String captureOutput(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = readAll(process);
        process.waitFor();
        return output;
    }

    private static String readAll(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }

    private static void sleepQuietly(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
