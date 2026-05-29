package ch.alpenflight.migrations.domain;

/**
 * Raised when a {@link MigrationUpload} aggregate is asked for a state
 * transition that the FSM forbids — e.g. supersede on an already-expired
 * row. Translated to HTTP {@code 409 Conflict} by
 * {@code MigrationHandshakeExceptionHandler}.
 */
public class IllegalUploadStateException extends RuntimeException {

    public IllegalUploadStateException(String message) {
        super(message);
    }
}
