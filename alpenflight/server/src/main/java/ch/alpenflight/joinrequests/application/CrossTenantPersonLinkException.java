package ch.alpenflight.joinrequests.application;

import java.util.UUID;

public class CrossTenantPersonLinkException extends RuntimeException {

    public CrossTenantPersonLinkException(UUID personId) {
        super("Person " + personId + " has no membership in the admin's club — cannot link");
    }
}
