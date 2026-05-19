package ch.alpenflight.clubs.domain;

/**
 * Raised when a request carries a {@code countryId} or {@code clubStateId}
 * that does not exist in the V2 reference catalog. Translated to HTTP 400
 * by {@link ch.alpenflight.clubs.web.ClubsExceptionHandler}.
 */
public class InvalidClubReferenceException extends RuntimeException {

    private final String field;

    public InvalidClubReferenceException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
