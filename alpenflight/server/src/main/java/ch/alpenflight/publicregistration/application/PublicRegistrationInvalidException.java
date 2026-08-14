package ch.alpenflight.publicregistration.application;

/**
 * A submitted registration does not satisfy the field contract
 * {@link PublicRegistrantDetails} enforces. Surfaces as 400; the message names
 * the offending field for the form, never anything about the club.
 */
public class PublicRegistrationInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PublicRegistrationInvalidException(String message) {
        super(message);
    }
}
