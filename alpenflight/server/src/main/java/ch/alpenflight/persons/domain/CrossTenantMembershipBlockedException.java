package ch.alpenflight.persons.domain;

/**
 * Thrown when a CLUB_ADMINISTRATOR attempts to soft-delete a Person who
 * has active {@code PersonClub} rows in tenants other than the caller's —
 * deleting would orphan another tenant's records. Sysadmin cross-cutting
 * hard-delete (if and when wired) cascades via {@code orphanRemoval}.
 *
 * <p>Mapped to HTTP 409 by the web-layer advice.
 */
public class CrossTenantMembershipBlockedException extends RuntimeException {

    public CrossTenantMembershipBlockedException(String message) {
        super(message);
    }
}
