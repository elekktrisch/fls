package ch.alpenflight.aircraft.domain;

import ch.alpenflight.platform.id.AircraftId;

public class AircraftNotFoundException extends RuntimeException {

    public AircraftNotFoundException(AircraftId id) {
        super("Aircraft not found: " + id);
    }
}
