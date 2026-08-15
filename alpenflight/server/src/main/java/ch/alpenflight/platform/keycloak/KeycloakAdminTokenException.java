package ch.alpenflight.platform.keycloak;

public class KeycloakAdminTokenException extends RuntimeException {

    public KeycloakAdminTokenException(String message) {
        super(message);
    }

    public KeycloakAdminTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
