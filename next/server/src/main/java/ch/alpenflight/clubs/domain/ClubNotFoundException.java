package ch.alpenflight.clubs.domain;

import ch.alpenflight.platform.id.ClubId;

/**
 * Thrown when a Clubs endpoint is asked to read / mutate a non-existent or
 * soft-deleted club. Translated to HTTP 404 by {@code ClubsExceptionHandler}
 * in {@code clubs.web}; the domain exception stays free of Spring web
 * imports per ADR 0023.
 */
public class ClubNotFoundException extends RuntimeException {

    public ClubNotFoundException(ClubId id) {
        super("Club not found: " + id);
    }
}
