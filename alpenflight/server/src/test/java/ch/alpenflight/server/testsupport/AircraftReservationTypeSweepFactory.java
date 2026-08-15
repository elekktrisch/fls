package ch.alpenflight.server.testsupport;

import ch.alpenflight.reservations.domain.AircraftReservationType;

final class AircraftReservationTypeSweepFactory {

    private AircraftReservationTypeSweepFactory() {}

    static AircraftReservationType build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        String unique = Long.toString(System.nanoTime(), 36);
        return AircraftReservationType.create(
                TenantTestContext.current().orElse(TenantTestContext.NO_TENANT),
                TenantScopedRowBuilders.SWEEP_PREFIX + "ARVT_" + unique,
                false,
                false,
                true,
                null);
    }
}
