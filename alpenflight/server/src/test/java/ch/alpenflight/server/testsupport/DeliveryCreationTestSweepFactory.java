package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.IgnoreFlags;
import ch.alpenflight.flights.domain.Flight;
import java.util.UUID;

/**
 * Minimal-object factory for {@link DeliveryCreationTest} consumed by the S-024
 * leakage sweep. The harness carries one non-tenant FK — {@code flight_id →
 * t_flight} (CASCADE) — so the factory seeds a Flight (and its cross-tenant
 * Aircraft) under the tenant the sweep is currently running as (or the fallback
 * V5 seed club when unscoped) through the production save path, so the FK
 * resolves even under the NO_TENANT sentinel.
 *
 * <p>Because the flight FK is satisfied under both the scoped and the fallback
 * club, the ONLY FK left unsatisfiable under NO_TENANT is the {@code @TenantId}
 * {@code operating_club_id} (resolved to the nil UUID, absent from
 * {@code t_club}) — so the sweep's fail-closed write assertion trips at
 * {@code fk_delivery_creation_test_operating_club_id} (V43 realigned that FK to
 * the convention name the sweep reconstructs), exactly the resolver-drift threat
 * the assertion guards.
 *
 * <p>{@code operating_club_id} is NOT set here — {@code create} does not accept
 * it (it is the discriminator); Hibernate's resolver fills it on INSERT. The
 * aggregate-internal {@code DeliveryCreationTestItem} children stay empty (no
 * dry-run is captured), so the harness row is the only insert and it fails at its
 * own tenant FK.
 */
final class DeliveryCreationTestSweepFactory {

    private static final UUID FALLBACK_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private DeliveryCreationTestSweepFactory() {}

    static DeliveryCreationTest build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID currentTenant = TenantTestContext.current().orElse(null);
        UUID fkClub = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? FALLBACK_CLUB
                : currentTenant;

        UUID aircraftId = ctx.seedAircraft(fkClub);
        Flight flight = ctx.seedFlight(fkClub, aircraftId);
        UUID flightId = flight.getId();
        if (flightId == null) {
            throw new IllegalStateException("Flight save returned a null id");
        }

        // operatingClubId placeholder = the tenant the resolver will resolve to
        // (current when scoped, NO_TENANT when unscoped). Hibernate's @TenantId
        // resolver is authoritative for operating_club_id on INSERT.
        UUID operatingClubPlaceholder =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;

        return DeliveryCreationTest.create(
                operatingClubPlaceholder,
                flightId,
                TenantScopedRowBuilders.SWEEP_PREFIX + "DCT_" + Long.toString(System.nanoTime(), 36),
                null,
                true,
                false,
                IgnoreFlags.none());
    }
}
