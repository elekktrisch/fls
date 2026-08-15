package ch.alpenflight.joinrequests.domain;

public class IllegalJoinRequestStateException extends RuntimeException {

    public IllegalJoinRequestStateException(String message) {
        super(message);
    }
}
