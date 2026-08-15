package ch.alpenflight.users.domain;

public class UserDirectoryException extends RuntimeException {
    public UserDirectoryException(String message) {
        super(message);
    }

    public UserDirectoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
