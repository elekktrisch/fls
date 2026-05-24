package ch.alpenflight.flights.domain;

/**
 * Sparse discriminator for the single Flight table. Sacred-cow legacy
 * mapping (per {@code FlightAircraftTypeValue.cs}): {@link #GLIDER} = 1,
 * {@link #TOW} = 2, {@link #MOTOR} = 4 — value 3 deliberately skipped (the
 * legacy {@code GliderWithMotor} category lives on Aircraft, NOT on Flight).
 *
 * <p>Persistence uses {@link FlightAircraftTypeConverter} with the explicit
 * {@code legacyId} mapping; do NOT use {@code @Enumerated(ORDINAL)} (which
 * is 0-indexed-contiguous and would assign 1 to TOW instead of GLIDER).
 */
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
