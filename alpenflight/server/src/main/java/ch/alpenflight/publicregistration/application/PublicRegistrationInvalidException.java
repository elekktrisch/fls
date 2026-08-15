package ch.alpenflight.publicregistration.application;

public class PublicRegistrationInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PublicRegistrationInvalidException(String message) {
        super(message);
    }
}
