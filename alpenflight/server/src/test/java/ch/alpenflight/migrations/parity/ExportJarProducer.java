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

public final class ExportJarProducer {

    // ext: migration-tool --password-env default
    static final String DB_PASSWORD_ENV = "ALPF_DB_PASSWORD";

    static final int NO_EXIT_VALUE = -1;

    private static final Duration STDERR_DRAIN_GRACE = Duration.ofSeconds(5);

    private static final ProcessBuilder.Redirect
            STDOUT_DISCARDED_SO_A_FULL_PIPE_CANNOT_WEDGE_THE_CHILD =
            ProcessBuilder.Redirect.DISCARD;

    private final Duration timeout;

    public ExportJarProducer(Duration timeout) {
        this.timeout = timeout;
    }

    public static List<String> exportCommand(
            Path jarPath, String jdbcUrl, String dbUser, Path handshakeFile, Path output) {
        return List.of(
                javaBinary(), "-jar", jarPath.toString(),
                "--jdbc-url", jdbcUrl,
                "--user", dbUser,
                "--handshake-file", handshakeFile.toString(),
                "--output", output.toString());
    }

    public Path produce(List<String> command, String dbPassword,
            Path outputPathTheCommandAlsoNames)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectOutput(STDOUT_DISCARDED_SO_A_FULL_PIPE_CANNOT_WEDGE_THE_CHILD);
        processBuilder.environment().put(DB_PASSWORD_ENV, dbPassword);
        Process process = processBuilder.start();
        closeStdinSoAChildAwaitingAPasswordFailsFastInsteadOfHanging(process);

        StringBuilder stderr = new StringBuilder();
        Thread stderrDrain =
                drainOnItsOwnThreadSoAFullPipeCannotWedgeTheChild(process.getErrorStream(), stderr);

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly().waitFor();
            stderrDrain.join(STDERR_DRAIN_GRACE.toMillis());
            throw new ExportJarProcessException(
                    "producer process timed out after " + timeout, NO_EXIT_VALUE,
                    stderr.toString());
        }
        stderrDrain.join(STDERR_DRAIN_GRACE.toMillis());

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ExportJarProcessException(
                    "producer process failed: " + exitCode, exitCode, stderr.toString());
        }
        if (!Files.exists(outputPathTheCommandAlsoNames)
                || Files.size(outputPathTheCommandAlsoNames) == 0L) {
            throw new ExportJarProcessException(
                    "producer produced an empty bundle: " + outputPathTheCommandAlsoNames, exitCode,
                    stderr.toString());
        }
        return outputPathTheCommandAlsoNames;
    }

    private static void closeStdinSoAChildAwaitingAPasswordFailsFastInsteadOfHanging(
            Process process) throws IOException {
        process.getOutputStream().close();
    }

    private static Thread drainOnItsOwnThreadSoAFullPipeCannotWedgeTheChild(
            InputStream stream, StringBuilder sink) {
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
