package ch.alpenflight.migration.tool;

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
