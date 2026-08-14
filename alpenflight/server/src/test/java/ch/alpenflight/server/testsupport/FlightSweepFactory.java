package ch.alpenflight.server.testsupport;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import java.util.UUID;

final class FlightSweepFactory {

    private static final UUID FALLBACK_MANAGING_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private FlightSweepFactory() {}

    static Flight build(SweepFixtureContext ctx) {
        UUID currentTenant = TenantTestContext.current().orElse(null);
        UUID aircraftManager = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? FALLBACK_MANAGING_CLUB
                : currentTenant;
        UUID aircraftId = ctx.seedAircraft(aircraftManager);
        return Flight.createGlider(aircraftId, FlightProcessState.NOT_PROCESSED.id(), emptyOps());
    }

    private static FlightOperationalData emptyOps() {
        return new FlightOperationalData(
                null, null, null, null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                false, false,
                null, null,
                null, null,
                null,
                null,
                null, null,
                false);
    }
}
