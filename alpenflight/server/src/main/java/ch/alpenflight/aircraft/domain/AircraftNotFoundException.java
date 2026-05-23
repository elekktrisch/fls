package ch.alpenflight.aircraft.domain;

import ch.alpenflight.platform.id.AircraftId;

/**
 * Thrown when an Aircraft endpoint is asked to read / mutate a non-existent
 * or soft-deleted aircraft. Translated to HTTP 404 by
 * {@code AircraftsExceptionHandler} in {@code aircraft.web}; the domain
 * exception stays free of Spring web imports per ADR 0023.
 */
public class AircraftNotFoundException extends RuntimeException {

    public AircraftNotFoundException(AircraftId id) {
        super("Aircraft not found: " + id);
    }
}
