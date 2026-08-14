package ch.alpenflight.server.testsupport;

import ch.alpenflight.flighttypes.domain.FlightType;

final class FlightTypeSweepFactory {

    private FlightTypeSweepFactory() {}

    @SuppressWarnings("unused")
    static FlightType build(SweepFixtureContext ctx) {
        String unique = Long.toString(System.nanoTime(), 36);
        return FlightType.register(
                TenantScopedRowBuilders.SWEEP_PREFIX + "FT_" + unique,
                null,
                false, false,
                false, false, false,
                true, false, false,
                false, false, false,
                null);
    }
}
