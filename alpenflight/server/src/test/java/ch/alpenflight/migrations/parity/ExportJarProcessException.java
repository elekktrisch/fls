package ch.alpenflight.migrations.parity;

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
