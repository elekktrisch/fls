package ch.alpenflight.migrations.parity;

/**
 * Raised when the spawned export-jar process fails — a non-zero exit, a
 * timeout, or a zero-exit-but-empty bundle. Carries the child's exit code and
 * drained stderr so the parity IT can surface the jar's structured failure
 * (AC4) before any upload or diff is attempted. {@code exitCode} is
 * {@code -1} when the process was killed on timeout (no exit value exists).
 */
public final class ExportJarProcessException extends RuntimeException {

    private final int exitCode;
    private final String stderr;

    public ExportJarProcessException(String message, int exitCode, String stderr) {
        super(message + (stderr.isBlank() ? "" : "\n--- producer stderr ---\n" + stderr));
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stderr() {
        return stderr;
    }
}
