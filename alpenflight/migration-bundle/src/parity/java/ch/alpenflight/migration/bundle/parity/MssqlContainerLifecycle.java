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
 * Drives a SQL Server container's lifecycle via {@code docker} CLI directly —
 * the same approach the {@code alpenflight/database/extract/} module uses.
 *
 * <p>Why not Testcontainers: the sandbox's Docker daemon enforces minimum API
 * 1.44 but Testcontainers' bundled docker-java negotiates API 1.32, which is
 * rejected before any container can boot. The {@code docker} CLI
 * auto-negotiates the daemon's actual API version, so shelling out is the
 * path of least resistance.
 *
 * <p>Container guarantees: random container name per JVM run, random host
 * port, shutdown hook removes the container even on JVM crash, connection
 * readiness poll capped at 90 s.
 */
public final class MssqlContainerLifecycle {

    private static final String IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    private static final String SA_PASSWORD = "ParityPa$$w0rd_2026";
    private static final int READINESS_TIMEOUT_SECONDS = 90;

    private final String containerName =
            "alpenflight-parity-mssql-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;

    public synchronized void start() {
        if (started) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::stopQuietly, "alpenflight-parity-mssql-shutdown"));
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
        return "jdbc:sqlserver://localhost:" + hostPort
                + ";encrypt=false;trustServerCertificate=true";
    }

    public String username() {
        return "sa";
    }

    public String password() {
        return SA_PASSWORD;
    }

    private void ensureStarted() {
        if (!started) {
            throw new IllegalStateException("MSSQL container not started — call start() first");
        }
    }

    private int readHostPort() {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                String output = captureOutput("docker", "port", containerName, "1433/tcp");
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
                "SQL Server in container " + containerName + " not ready within "
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
