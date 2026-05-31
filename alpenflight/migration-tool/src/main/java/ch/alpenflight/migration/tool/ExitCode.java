package ch.alpenflight.migration.tool;

/**
 * Stable process exit codes. Operators script the export; a small, frozen
 * taxonomy lets a wrapper distinguish "fix the handshake" from "fix the DB
 * connection" without parsing stderr. The full S-139 follow-up taxonomy
 * (per-phase granularity) is deferred — this is the load-bearing minimum.
 *
 * <p>{@code OK} and {@code USAGE} match the picocli convention (0 / 2) so a
 * bad command line surfaces the standard usage exit.
 */
public enum ExitCode {
    OK(0),
    USAGE(2),
    PUBLIC_KEY_INVALID(3),
    JDBC_CONNECT_FAILED(4),
    OUTPUT_EXISTS(5),
    IO_ERROR(6);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
