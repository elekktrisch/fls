package ch.alpenflight.aircraft.domain;

import java.util.UUID;

public record AircraftSaved(UUID aircraftId) {

    public AircraftSaved {
        if (aircraftId == null) {
            throw new IllegalArgumentException("aircraftId must not be null");
        }
    }
}
