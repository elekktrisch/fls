package ch.alpenflight.flights.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public enum FlightAirState {

    NEW(0),
    FLIGHT_PLAN_OPEN(5),
    MIGHT_BE_STARTED(8),
    STARTED(10),
    MIGHT_BE_LANDED_OR_IN_AIR(15),
    LANDED(20),
    FLIGHT_PLAN_CLOSED(25);

    private final short legacyCode;

    FlightAirState(int legacyCode) {
        this.legacyCode = (short) legacyCode;
    }

    public short legacyCode() {
        return legacyCode;
    }

    public static FlightAirState compute(@Nullable Instant ldgDateTime,
                                         @Nullable Instant startDateTime,
                                         boolean noLdgTimeInformation,
                                         boolean noStartTimeInformation,
                                         @Nullable Instant flightPlanOpenedOn) {
        if (ldgDateTime != null) {
            return LANDED;
        }
        if (noLdgTimeInformation && startDateTime != null) {
            return MIGHT_BE_LANDED_OR_IN_AIR;
        }
        if (startDateTime != null) {
            return STARTED;
        }
        if (noStartTimeInformation) {
            return MIGHT_BE_STARTED;
        }
        if (flightPlanOpenedOn != null) {
            return FLIGHT_PLAN_OPEN;
        }
        return NEW;
    }
}
