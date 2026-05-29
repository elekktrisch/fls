package ch.alpenflight.migrations.application;

/**
 * Raised when an authenticated JWT carries no resolvable
 * {@code t_user.id} (no matching {@code keycloak_sub} row). The S-140
 * handshake assumes a verified-email user with a materialised row.
 * Translated to HTTP {@code 403 Forbidden} by
 * {@code MigrationHandshakeExceptionHandler}.
 */
public class UnknownPrincipalException extends RuntimeException {

    public UnknownPrincipalException(String message) {
        super(message);
    }
}
