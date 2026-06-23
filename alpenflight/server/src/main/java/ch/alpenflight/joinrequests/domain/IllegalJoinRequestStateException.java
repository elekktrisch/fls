package ch.alpenflight.joinrequests.domain;

/**
 * Raised when a {@link JoinRequest} is asked for a transition the FSM forbids —
 * e.g. approving an already-withdrawn request, or deciding one that is not
 * {@link JoinRequestStatus#PENDING}. The web layer maps it to HTTP 409
 * (the request is no longer in a decidable state).
 */
public class IllegalJoinRequestStateException extends RuntimeException {

    public IllegalJoinRequestStateException(String message) {
        super(message);
    }
}
