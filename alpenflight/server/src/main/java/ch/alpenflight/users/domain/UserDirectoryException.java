package ch.alpenflight.users.domain;

public class UserDirectoryException extends RuntimeException {
    public UserDirectoryException(String messageWithoutUpstreamResponseBody) {
        super(messageWithoutUpstreamResponseBody);
    }

    public UserDirectoryException(String messageWithoutUpstreamResponseBody, Throwable cause) {
        super(messageWithoutUpstreamResponseBody, cause);
    }
}
