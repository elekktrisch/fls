package ch.alpenflight.flights.domain;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Computed air-state values. Sacred-cow rule: never stored — derived by
 * {@link Flight#airState()} from {@code (ldgDateTime, startDateTime,
 * noLdgTimeInformation, noStartTimeInformation, flightPlanOpenedOn)} per
 * legacy {@code Flight.cs:175-206}.
 *
 * <p>{@code legacyCode} values mirror {@code FLS.Data.WebApi.Flight.FlightAirState}
 * (0/5/8/10/15/20/25); stable input to e2e parity tests and the SPA's filter
 * dropdown when the legacy reading shape needs reconciliation.
 *
 * <p>{@link #FLIGHT_PLAN_CLOSED} stays in the enum but is never emitted by
 * {@link Flight#airState()}; legacy compute also never returns it. The value
 * is reachable only through process-state driven downstream operations.
 */
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

    /**
     * Pure compute over the aggregate's air-state inputs per legacy
     * {@code Flight.cs:175-206}. Branch ordering is load-bearing:
     * {@code ldgDateTime} wins over any flag combination, then
     * {@code noLdgTimeInformation+startDateTime}, then {@code startDateTime},
     * then {@code noStartTimeInformation}, then {@code flightPlanOpenedOn}.
     * {@link #FLIGHT_PLAN_CLOSED} is never emitted here — it is a process-
     * state driven downstream value.
     *
     * <p>Shared between {@link Flight#airState()} and the list-row projection
     * so detail + list views always agree.
     */
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
