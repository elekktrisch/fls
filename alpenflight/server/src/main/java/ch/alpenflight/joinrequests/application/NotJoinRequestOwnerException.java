package ch.alpenflight.joinrequests.application;

public class NotJoinRequestOwnerException extends RuntimeException {

    public NotJoinRequestOwnerException() {
        super("Only the request's own pilot may withdraw it");
    }
}
