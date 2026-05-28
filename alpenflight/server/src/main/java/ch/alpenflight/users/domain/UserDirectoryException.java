package ch.alpenflight.users.domain;

/**
 * Wraps user-directory upstream failures. Exception messages must NEVER
 * carry the upstream response body — directory error payloads occasionally
 * include the user's email or service-account client name, which would leak
 * into request logs. Status codes are fine; bodies are not.
 *
 * <p>Mapped to HTTP 502 by the {@code UsersExceptionHandler}.
 */
public class UserDirectoryException extends RuntimeException {
    public UserDirectoryException(String message) {
        super(message);
    }

    public UserDirectoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
