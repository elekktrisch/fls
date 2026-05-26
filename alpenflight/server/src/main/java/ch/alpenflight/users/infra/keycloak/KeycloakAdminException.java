package ch.alpenflight.users.infra.keycloak;

/**
 * Wraps Keycloak admin-REST failures. Exception messages must NEVER carry
 * the upstream response body — Keycloak's error payloads occasionally
 * include the user's email or the service-account client name, which would
 * leak into request logs. Status codes are fine; bodies are not.
 */
public class KeycloakAdminException extends RuntimeException {
    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
