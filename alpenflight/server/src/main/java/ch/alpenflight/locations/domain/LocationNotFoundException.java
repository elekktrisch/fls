package ch.alpenflight.locations.domain;

import ch.alpenflight.platform.id.LocationId;

/**
 * Thrown when a Locations endpoint is asked to read / mutate a non-existent
 * or soft-deleted location. Translated to HTTP 404 by
 * {@code LocationsExceptionHandler} in {@code locations.web}; the domain
 * exception stays free of Spring web imports per ADR 0023.
 */
public class LocationNotFoundException extends RuntimeException {

    public LocationNotFoundException(LocationId id) {
        super("Location not found: " + id);
    }
}
