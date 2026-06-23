package ch.alpenflight.joinrequests.application;

import java.util.UUID;

/**
 * Thrown when a withdraw targets a join-request id that does not exist (S-178).
 * Translated to HTTP 404.
 */
public class JoinRequestNotFoundException extends RuntimeException {

    public JoinRequestNotFoundException(UUID id) {
        super("Join request not found: " + id);
    }
}
