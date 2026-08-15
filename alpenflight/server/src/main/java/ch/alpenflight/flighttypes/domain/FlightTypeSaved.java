package ch.alpenflight.flighttypes.domain;

import java.util.UUID;

public record FlightTypeSaved(UUID flightTypeId) {

    public FlightTypeSaved {
        if (flightTypeId == null) {
            throw new IllegalArgumentException("flightTypeId must not be null");
        }
    }
}
