package ch.alpenflight.users.domain;

/**
 * 409 Conflict on the Users surface. Covers the lifecycle refusals named in
 * S-052's edge-cases section:
 * <ul>
 *   <li>Self-delete refused.</li>
 *   <li>Removing the last CLUB_ADMINISTRATOR of a club refused.</li>
 *   <li>Username already taken in Keycloak when no local row exists yet
 *       (invite-then-reconcile path).</li>
 * </ul>
 */
public class UserConflictException extends RuntimeException {
    public UserConflictException(String message) {
        super(message);
    }
}
