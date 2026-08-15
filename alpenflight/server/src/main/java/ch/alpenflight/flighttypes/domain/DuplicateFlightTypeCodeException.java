package ch.alpenflight.flighttypes.domain;

public class DuplicateFlightTypeCodeException extends RuntimeException {

    public DuplicateFlightTypeCodeException(String flightCode) {
        super("FlightType code already in use: " + flightCode);
    }
}
