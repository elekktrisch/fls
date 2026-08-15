package ch.alpenflight.migration.tool;

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
