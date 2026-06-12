package ch.alpenflight.server.testsupport;

import ch.alpenflight.flighttypes.domain.FlightType;

/**
 * Minimal-object factory for {@link FlightType} consumed by the S-024
 * leakage sweep. Name uniqueness is per-tenant (V11 partial UNIQUE) — the
 * suffix mixes nanoTime + the SWEEP_PREFIX to keep cross-test runs isolated
 * within a tenant.
 */
final class FlightTypeSweepFactory {

    private FlightTypeSweepFactory() {}

    @SuppressWarnings("unused") // ctx unused — FlightType has no FK reference data to look up.
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
