package ch.alpenflight.flights.domain;

/**
 * Report-facing flight category, mirroring the legacy
 * {@code FLS.Data.WebApi.Flight.FlightCategory} string enum
 * ({@code FlightReportDataRecord.cs:60-61}). Derived from the flight's
 * {@link FlightAircraftType} discriminator at projection time — never stored.
 *
 * <p>The new stack only persists three discriminator values (GLIDER / TOW /
 * MOTOR); {@link #OTHER} / {@link #UNKNOWN} exist to preserve the legacy
 * category vocabulary the report contract exposes (the legacy projection
 * could yield them from out-of-range {@code FlightAircraftType} ints, which
 * the V3 converter now rejects). They stay so a wire consumer's parser sees
 * the same closed set.
 */
public enum FlightCategory {
    GLIDER,
    TOW,
    MOTOR,
    OTHER,
    UNKNOWN;

    /** Maps the persisted discriminator to its report category. */
    public static FlightCategory of(FlightAircraftType type) {
        return switch (type) {
            case GLIDER -> GLIDER;
            case TOW -> TOW;
            case MOTOR -> MOTOR;
        };
    }
}
