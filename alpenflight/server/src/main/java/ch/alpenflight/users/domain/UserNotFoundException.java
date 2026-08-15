package ch.alpenflight.users.domain;

import ch.alpenflight.platform.id.UserId;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UserId id) {
        super("User " + id + " not found");
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}
