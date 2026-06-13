package ch.alpenflight.server.testsupport;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import java.util.UUID;

/**
 * Minimal-object factory for {@link Flight} consumed by the S-024 leakage
 * sweep. The Flight's initial process state is the {@code NOT_PROCESSED}
 * canonical UUID off the {@link FlightProcessState} enum; its cross-tenant
 * Aircraft FK is seeded through the production save path (Aircraft is
 * cross-tenant per S-058 reverting S-159, so the aircraft row can sit under
 * any club — we use the sweep's tenant when one is set, otherwise the V5 seed
 * club). Air-state is computed (S-060), so no seed lookup needed for that
 * surface. The Flight's own {@code operating_club_id} FK (its {@code @TenantId})
 * is the one that fails fail-closed under {@code NO_TENANT} — the sweep's
 * negative-path assertion.
 */
final class FlightSweepFactory {

    private static final UUID FALLBACK_MANAGING_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private FlightSweepFactory() {}

    static Flight build(SweepFixtureContext ctx) {
        UUID currentTenant = TenantTestContext.current().orElse(null);
        // Aircraft is cross-tenant; seed under the current tenant if one is
        // bound, otherwise fall back to the V5 seed club so the aircraft FK
        // resolves even under NO_TENANT.
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
