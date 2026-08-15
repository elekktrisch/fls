package ch.alpenflight.flights.domain;

import ch.alpenflight.platform.id.FlightId;

public class FlightNotFoundException extends RuntimeException {

    public FlightNotFoundException(FlightId id) {
        super("Flight " + id.toExternal() + " not found");
    }

    public FlightNotFoundException(String message) {
        super(message);
    }
}
