package ch.alpenflight.flighttypes.domain;

public class DuplicateFlightTypeNameException extends RuntimeException {

    public DuplicateFlightTypeNameException(String flightTypeName) {
        super("FlightType name already in use: " + flightTypeName);
    }
}
