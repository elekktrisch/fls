package ch.alpenflight.joinrequests.application;

/**
 * Thrown when a withdraw is attempted by a principal whose KC sub does not
 * own the targeted request (S-178). Translated to HTTP 403 — only the pilot
 * who filed a request may withdraw it.
 */
public class NotJoinRequestOwnerException extends RuntimeException {

    public NotJoinRequestOwnerException() {
        super("Only the request's own pilot may withdraw it");
    }
}
