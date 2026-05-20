package ch.alpenflight.locations.domain;

/**
 * Raised when a Location request carries a {@code countryId} or
 * {@code locationTypeId} that does not exist in the reference catalog.
 * Translated to HTTP 400 by
 * {@link ch.alpenflight.locations.web.LocationsExceptionHandler}.
 */
public class InvalidLocationReferenceException extends RuntimeException {

    private final String field;

    public InvalidLocationReferenceException(String field) {
        super("Unknown " + field);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
