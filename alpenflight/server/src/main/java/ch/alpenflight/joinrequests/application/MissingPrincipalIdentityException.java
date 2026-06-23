package ch.alpenflight.joinrequests.application;

/**
 * Thrown when a submit's JWT lacks an identity claim the aggregate requires
 * ({@code email}; {@code friendlyName} is derived with fallbacks). A
 * well-formed Keycloak signup token always carries it, so this is a
 * malformed-token guard → HTTP 400.
 */
public class MissingPrincipalIdentityException extends RuntimeException {

    public MissingPrincipalIdentityException(String claim) {
        super("JWT is missing the required identity claim: " + claim);
    }
}
