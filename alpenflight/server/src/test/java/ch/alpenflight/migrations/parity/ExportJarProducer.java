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
 * the parity IT exercises the production producer codepath end-to-end. The
 * subprocess reads the legacy database, writes the encrypted ALPF bundle, and
 * exits; this class never decrypts — the real ingest pipeline does.
 *
 * <p>Three failure modes map to {@link ExportJarProcessException} (AC4): a
 * non-zero exit, a {@code waitFor} timeout (the child is {@code destroyForcibly}
 * killed so a hung JDBC connect can't drain the walltime budget), and a
 * zero-exit-but-empty bundle. stderr is drained on a separate thread so a full
 * pipe can't deadlock the child.
 */
public final class ExportJarProducer {

    /**
     * The export jar's {@code --password-env} default. The DB password travels
     * through this environment variable, never argv — argv leaks to {@code ps}.
     */
    static final String DB_PASSWORD_ENV = "ALPF_DB_PASSWORD";

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
     */
    public Path produce(List<String> command, String dbPassword, Path expectedOutput)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
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
            stderrDrain.join();
            throw new ExportJarProcessException(
                    "producer process timed out after " + timeout, -1, stderr.toString());
        }
        stderrDrain.join();

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
