package ch.alpenflight.platform.keycloak;

/**
 * Raised by {@link KeycloakAdminTokenSupplier} when the service-account
 * token endpoint refuses or returns an unusable response. Lives in the
 * shared platform module so adapters across business modules (users,
 * tenancy) can both surface upstream-token failures through a common
 * exception type without crossing each other's boundaries.
 */
public class KeycloakAdminTokenException extends RuntimeException {

    public KeycloakAdminTokenException(String message) {
        super(message);
    }

    public KeycloakAdminTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
