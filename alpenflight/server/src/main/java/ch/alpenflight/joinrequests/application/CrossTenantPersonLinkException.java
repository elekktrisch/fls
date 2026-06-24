package ch.alpenflight.joinrequests.application;

import java.util.UUID;

/**
 * Thrown when an approve links a {@code personId} that has no alive membership
 * in the admin's tenant (S-178). Translated to HTTP 409 — the admin picked a
 * Person from another club, which would silently relocate identity across
 * tenants.
 */
public class CrossTenantPersonLinkException extends RuntimeException {

    public CrossTenantPersonLinkException(UUID personId) {
        super("Person " + personId + " has no membership in the admin's club — cannot link");
    }
}
