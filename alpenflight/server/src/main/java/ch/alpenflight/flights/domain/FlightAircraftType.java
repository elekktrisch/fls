package ch.alpenflight.flights.domain;

public enum FlightAircraftType {
    GLIDER(1),
    TOW(2),
    MOTOR(4);

    private final short legacyId;

    FlightAircraftType(int legacyId) {
        this.legacyId = (short) legacyId;
    }

    public short legacyId() {
        return legacyId;
    }

    public static FlightAircraftType fromLegacyId(short id) {
        for (FlightAircraftType t : values()) {
            if (t.legacyId == id) {
                return t;
            }
        }
        throw new IllegalArgumentException(
                "Unknown FlightAircraftType legacy id: " + id
                        + " (expected one of {1, 2, 4})");
    }
}
