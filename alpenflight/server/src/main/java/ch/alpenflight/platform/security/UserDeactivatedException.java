package ch.alpenflight.platform.security;

public class UserDeactivatedException extends RuntimeException {

    public UserDeactivatedException(String message) {
        super(message);
    }
}
