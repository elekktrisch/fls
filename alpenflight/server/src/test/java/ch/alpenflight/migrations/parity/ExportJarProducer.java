package ch.alpenflight.migrations.parity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spawns the real {@code migration-tool} export jar (S-139) as a subprocess so
 * the parity IT exercises the production producer codepath; this class never
 * decrypts — the real ingest pipeline does. Failure modes and their mapping
 * live on {@link #produce} and {@link ExportJarProcessException}.
 *
 * <p>Two non-obvious invariants: the child's stderr is drained on a separate
 * thread and its stdout is discarded (a full pipe on either stream would
 * otherwise deadlock the child past {@code waitFor}), and a timed-out child is
 * {@code destroyForcibly} killed so a hung JDBC connect fails attributably
 * instead of draining the walltime budget.
 */
public final class ExportJarProducer {

    /**
     * The export jar's {@code --password-env} default. The DB password travels
     * through this environment variable, never argv — argv leaks to {@code ps}.
     */
    static final String DB_PASSWORD_ENV = "ALPF_DB_PASSWORD";

    /** {@link ExportJarProcessException#exitCode()} when the child was killed on timeout. */
    static final int NO_EXIT_VALUE = -1;

    /** Grace for the stderr-drain thread to finish after the child exits / is killed. */
    private static final Duration STDERR_DRAIN_GRACE = Duration.ofSeconds(5);

    private final Duration timeout;

    public ExportJarProducer(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * The canonical jar invocation. The password is deliberately absent — it is
     * supplied to {@link #produce} and delivered via {@link #DB_PASSWORD_ENV}.
     */
    public static List<String> exportCommand(
            Path jarPath, String jdbcUrl, String dbUser, Path handshakeFile, Path output) {
        return List.of(
                javaBinary(), "-jar", jarPath.toString(),
                "--jdbc-url", jdbcUrl,
                "--user", dbUser,
                "--handshake-file", handshakeFile.toString(),
                "--output", output.toString());
    }

    /**
     * Runs {@code command} with {@code dbPassword} in the environment, returning
     * {@code expectedOutput} once the process exits zero and the file is
     * non-empty. Throws {@link ExportJarProcessException} on any failure mode.
     * {@code expectedOutput} must be the same path the command's {@code --output}
     * names — the caller keeps them in sync (the IT composes both from one path).
     */
    public Path produce(List<String> command, String dbPassword, Path expectedOutput)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        // Discard the child's stdout (jar status lines) so it can never fill the
        // pipe buffer and wedge the process; stderr is drained below for failures.
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // Inherits the parent environment by design (PATH etc.); the password
        // rides DB_PASSWORD_ENV, never argv.
        processBuilder.environment().put(DB_PASSWORD_ENV, dbPassword);
        Process process = processBuilder.start();
        // No password on stdin — it rides the env var. Close stdin so a child
        // that falls through to the stdin branch fails fast instead of hanging.
        process.getOutputStream().close();

        StringBuilder stderr = new StringBuilder();
        Thread stderrDrain = drainAsync(process.getErrorStream(), stderr);

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly().waitFor();
            stderrDrain.join(STDERR_DRAIN_GRACE.toMillis());
            throw new ExportJarProcessException(
                    "producer process timed out after " + timeout, NO_EXIT_VALUE,
                    stderr.toString());
        }
        stderrDrain.join(STDERR_DRAIN_GRACE.toMillis());

        // Any non-zero exit is a producer failure (stricter than the AC's
        // "exit≠0 AND stderr non-empty" — an exit code alone is dispositive).
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ExportJarProcessException(
                    "producer process failed: " + exitCode, exitCode, stderr.toString());
        }
        if (!Files.exists(expectedOutput) || Files.size(expectedOutput) == 0L) {
            throw new ExportJarProcessException(
                    "producer produced an empty bundle: " + expectedOutput, exitCode,
                    stderr.toString());
        }
        return expectedOutput;
    }

    private static Thread drainAsync(InputStream stream, StringBuilder sink) {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException drainFailure) {
                sink.append("[stderr drain interrupted: ").append(drainFailure.getMessage())
                        .append("]\n");
            }
        }, "export-jar-stderr-drain");
        drain.setDaemon(true);
        drain.start();
        return drain;
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
