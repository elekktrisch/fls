package ch.alpenflight.clubs.domain;

/**
 * Service-layer signal that a club slug collides with an existing row.
 * Translated to HTTP 409 by {@code ClubsExceptionHandler} in
 * {@code clubs.web}; the domain exception stays free of Spring web imports
 * per ADR 0023.
 */
public class SlugAlreadyExistsException extends RuntimeException {

    public SlugAlreadyExistsException(String slug) {
        super("Club slug already in use: " + slug);
    }
}
