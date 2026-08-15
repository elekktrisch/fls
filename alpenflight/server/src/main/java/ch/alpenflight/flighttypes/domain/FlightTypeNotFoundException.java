package ch.alpenflight.flighttypes.domain;

import ch.alpenflight.platform.id.FlightTypeId;

public class FlightTypeNotFoundException extends RuntimeException {

    public FlightTypeNotFoundException(FlightTypeId id) {
        super("FlightType not found: " + id);
    }
}
