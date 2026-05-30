package ch.alpenflight.migrations.domain;

/**
 * Raised when a {@link MigrationRun} aggregate is asked for a state
 * transition the FSM forbids — e.g. marking a {@code FAILED} run as
 * {@code COMPLETED}. Programming error inside the ingest pipeline; the
 * exception handler maps to HTTP 500.
 */
public class IllegalRunStateException extends RuntimeException {

    public IllegalRunStateException(String message) {
        super(message);
    }
}
