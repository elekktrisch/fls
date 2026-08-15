package ch.alpenflight.flights.domain;

public enum FlightCategory {
    GLIDER,
    TOW,
    MOTOR,
    OTHER,
    UNKNOWN;

    public static FlightCategory of(FlightAircraftType type) {
        return switch (type) {
            case GLIDER -> GLIDER;
            case TOW -> TOW;
            case MOTOR -> MOTOR;
        };
    }
}
