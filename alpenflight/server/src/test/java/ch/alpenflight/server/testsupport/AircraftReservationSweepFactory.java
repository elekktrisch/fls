package ch.alpenflight.server.testsupport;

import ch.alpenflight.reservations.domain.AircraftReservation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class AircraftReservationSweepFactory {

    private static final UUID SEED_CLUB_FOR_FK_PARENTS_WHEN_UNSCOPED =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private AircraftReservationSweepFactory() {}

    static AircraftReservation build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID currentTenant = TenantTestContext.current().orElse(null);
        UUID fkClub = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? SEED_CLUB_FOR_FK_PARENTS_WHEN_UNSCOPED
                : currentTenant;

        UUID aircraftId = ctx.seedAircraft(fkClub);
        UUID pilotPersonId = ctx.seedPerson();
        UUID locationId = ctx.seedLocation(fkClub);
        UUID reservationTypeId = ctx.seedReservationType(fkClub);

        Instant start = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(2, ChronoUnit.HOURS);

        UUID tenantTheResolverWillAssignOnInsert =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;

        return AircraftReservation.create(
                tenantTheResolverWillAssignOnInsert,
                aircraftId,
                pilotPersonId,
                locationId,
                reservationTypeId,
                null,
                start,
                end,
                false,
                null,
                TenantScopedRowBuilders.SWEEP_PREFIX + "RES");
    }
}
