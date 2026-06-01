package ch.alpenflight.migration.tool;

/**
 * Carries a stable {@link ExitCode} out of the pipeline to the CLI shell,
 * which translates it to a process exit status. Distinct from picocli's
 * own {@code ParameterException} (USAGE) so a runtime failure (bad key, DB
 * unreachable, output collision) maps to its own code.
 */
public final class ExportException extends RuntimeException {

    private final ExitCode exitCode;

    public ExportException(ExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public ExportException(ExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public ExitCode exitCode() {
        return exitCode;
    }
}
