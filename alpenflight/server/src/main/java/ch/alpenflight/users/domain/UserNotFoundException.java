package ch.alpenflight.users.domain;

import ch.alpenflight.platform.id.UserId;

/**
 * Thrown when a User lookup yields no row visible to the caller's tenant.
 * Mapped to HTTP 404 — never 403 — to keep cross-tenant existence opaque.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UserId id) {
        super("User " + id + " not found");
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}
