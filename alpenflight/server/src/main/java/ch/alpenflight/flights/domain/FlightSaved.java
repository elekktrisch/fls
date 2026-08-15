package ch.alpenflight.flights.domain;

import java.util.UUID;

public record FlightSaved(UUID flightId) {

    public FlightSaved {
        if (flightId == null) {
            throw new IllegalArgumentException("flightId must not be null");
        }
    }
}
