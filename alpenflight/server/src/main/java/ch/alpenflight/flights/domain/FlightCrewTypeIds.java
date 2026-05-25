package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Canonical UUIDs for the {@code flight_crew_type} seed table (V3).
 * Centralised so service / validator / mapper share one source of truth —
 * a seed re-ID would otherwise need a per-call-site update.
 */
public final class FlightCrewTypeIds {

    public static final UUID PILOT_OR_STUDENT =
            UUID.fromString("019e2e15-2c00-76b0-8000-0000000036b0");
    public static final UUID CO_PILOT =
            UUID.fromString("019e2e15-2c00-76b1-8000-0000000036b1");
    public static final UUID FLIGHT_INSTRUCTOR =
            UUID.fromString("019e2e15-2c00-76b2-8000-0000000036b2");
    public static final UUID PASSENGER =
            UUID.fromString("019e2e15-2c00-76b3-8000-0000000036b3");
    public static final UUID WINCH_OPERATOR =
            UUID.fromString("019e2e15-2c00-76b4-8000-0000000036b4");
    public static final UUID OBSERVER =
            UUID.fromString("019e2e15-2c00-76b5-8000-0000000036b5");
    public static final UUID FLIGHT_COST_INVOICE_RECIPIENT =
            UUID.fromString("019e2e15-2c00-76b6-8000-0000000036b6");

    private FlightCrewTypeIds() {}
}
