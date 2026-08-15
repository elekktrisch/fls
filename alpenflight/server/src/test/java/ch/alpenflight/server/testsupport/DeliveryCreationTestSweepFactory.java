package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.DeliveryCreationTest;
import ch.alpenflight.accounting.domain.IgnoreFlags;
import ch.alpenflight.flights.domain.Flight;
import java.util.UUID;

final class DeliveryCreationTestSweepFactory {

    private static final UUID SEED_CLUB_FOR_FK_PARENTS_WHEN_UNSCOPED =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private DeliveryCreationTestSweepFactory() {}

    static DeliveryCreationTest build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID currentTenant = TenantTestContext.current().orElse(null);
        UUID fkClub = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? SEED_CLUB_FOR_FK_PARENTS_WHEN_UNSCOPED
                : currentTenant;

        UUID aircraftId = ctx.seedAircraft(fkClub);
        Flight flight = ctx.seedFlight(fkClub, aircraftId);
        UUID flightId = flight.getId();
        if (flightId == null) {
            throw new IllegalStateException("Flight save returned a null id");
        }

        UUID tenantTheResolverWillAssignOnInsert =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;

        return DeliveryCreationTest.create(
                tenantTheResolverWillAssignOnInsert,
                flightId,
                TenantScopedRowBuilders.SWEEP_PREFIX + "DCT_" + Long.toString(System.nanoTime(), 36),
                null,
                true,
                false,
                IgnoreFlags.none());
    }
}
