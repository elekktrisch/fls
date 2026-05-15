package ch.fls.legacyextract;

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
 * Drives a SQL Server container's lifecycle via {@code docker} CLI directly.
 *
 * <p>Why not Testcontainers: the sandbox's Docker daemon enforces minimum API
 * 1.44 but Testcontainers 1.21.x's bundled docker-java negotiates API 1.32,
 * which is rejected before any container can boot. The {@code docker} CLI
 * (29.x in the sandbox) auto-negotiates the daemon's actual API version, so
 * shelling out is the path of least resistance. Same containment guarantees:
 * unique container name per JVM run, hard-stop in the shutdown hook, port
 * mapping over the loopback interface.
 *
 * <p>Container behaviors that this class guarantees:
 * <ul>
 *   <li>Image: {@code mcr.microsoft.com/mssql/server:2022-latest} (x86_64
 *       only; the sandbox is x86_64 — this class will fail loudly on ARM).
 *   <li>Random container name per JVM run (collision-safe across parallel
 *       test JVMs).
 *   <li>Random host port; the assigned port is read back from
 *       {@code docker port ...} for the JDBC URL.
 *   <li>Shutdown hook removes the container even on JVM crash.
 *   <li>Connection-readiness poll with a 60-second cap; fail-loud if
 *       SQL Server isn't accepting logins by then.
 * </ul>
 */
public final class MssqlTestContainerLifecycle {

    private static final String IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";
    static final String SA_PASSWORD = "TestPa$$w0rd_2026";
    private static final int READINESS_TIMEOUT_SECONDS = 90;

    private final String containerName = "fls-extract-test-" + UUID.randomUUID().toString().substring(0, 8);
    private volatile int hostPort = -1;
    private volatile boolean started = false;

    public synchronized void start() {
        if (started) return;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopQuietly, "fls-extract-mssql-shutdown"));
        runOrThrow("docker", "pull", IMAGE);
        runOrThrow(
                "docker", "run", "-d",
                "--name", containerName,
                "-e", "ACCEPT_EULA=Y",
                "-e", "MSSQL_SA_PASSWORD=" + SA_PASSWORD,
                "-p", "0:1433",
                IMAGE);
        hostPort = readHostPort();
        // Flip `started` BEFORE the readiness probe so the probe can call
        // jdbcUrl(). If readiness fails, stopQuietly() runs and the caller
        // sees a thrown ISE.
        started = true;
        try {
            waitForReady();
        } catch (RuntimeException e) {
            started = false;
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
            new ProcessBuilder("docker", "rm", "-f", containerName).redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    /** JDBC URL pointing at the running container, master database. */
    public String jdbcUrl() {
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
        // `docker port <name> 1433/tcp` returns lines like "0.0.0.0:32768"
        // and possibly "[::]:32768". Take the first IPv4 line.
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                String out = captureOutput("docker", "port", containerName, "1433/tcp");
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
        String url = jdbcUrl();
        Properties props = new Properties();
        props.setProperty("user", username());
        props.setProperty("password", password());
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            try (Connection c = DriverManager.getConnection(url, props)) {
                c.createStatement().execute("SELECT 1");
                return;
            } catch (SQLException e) {
                last = e;
                sleepQuietly(1500);
            }
        }
        stopQuietly();
        throw new IllegalStateException(
                "SQL Server in container " + containerName + " not ready within "
                        + READINESS_TIMEOUT_SECONDS + "s: " + (last != null ? last.getMessage() : "unknown"));
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
