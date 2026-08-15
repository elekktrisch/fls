package ch.alpenflight.joinrequests.application;

import java.util.UUID;

public class JoinRequestNotFoundException extends RuntimeException {

    public JoinRequestNotFoundException(UUID id) {
        super("Join request not found: " + id);
    }
}
