package ch.alpenflight.server.testsupport;

import ch.alpenflight.reservations.domain.AircraftReservation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Minimal-object factory for {@link AircraftReservation} consumed by the S-024
 * leakage sweep (J-5 T-29). The reservation carries four non-tenant FKs that
 * must resolve at INSERT, so the factory seeds them through the production save
 * path, keyed to the tenant the sweep is currently running as (mirroring how
 * {@link FlightSweepFactory} seeds the cross-tenant Aircraft):
 *
 * <ul>
 *   <li><b>aircraft</b> — cross-tenant; seeded under the current tenant (or the
 *       fallback V5 seed club when unscoped) so the FK resolves even under the
 *       NO_TENANT sentinel;</li>
 *   <li><b>pilot person</b> — cross-tenant {@code t_person} row;</li>
 *   <li><b>location</b> — tenant-scoped; seeded under the same club;</li>
 *   <li><b>reservation type</b> — tenant-scoped; seeded under the same club.</li>
 * </ul>
 *
 * <p>Because every non-tenant FK is satisfied under both the scoped and the
 * fallback club, the ONLY FK left unsatisfiable under NO_TENANT is the
 * {@code @TenantId} {@code operating_club_id} (resolved to the nil UUID, absent
 * from {@code t_club}) — so the sweep's fail-closed write assertion trips at
 * {@code fk_aircraft_reservation_operating_club_id} (V32 realigned that FK to
 * the convention name the sweep reconstructs), exactly the resolver-drift threat
 * the assertion guards.
 *
 * <p>The {@code reservation_range} column is {@code GENERATED ALWAYS} in V4 and
 * the aggregate marks it {@code @Transient}, so the builder never writes it.
 */
final class AircraftReservationSweepFactory {

    private static final UUID FALLBACK_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private AircraftReservationSweepFactory() {}

    static AircraftReservation build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID currentTenant = TenantTestContext.current().orElse(null);
        // Seed FK parents under the bound tenant, or the fallback seed club when
        // unscoped (NO_TENANT) so only the tenant FK fails fail-closed.
        UUID fkClub = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? FALLBACK_CLUB
                : currentTenant;

        UUID aircraftId = ctx.seedAircraft(fkClub);
        UUID pilotPersonId = ctx.seedPerson();
        UUID locationId = ctx.seedLocation(fkClub);
        UUID reservationTypeId = ctx.seedReservationType(fkClub);

        Instant start = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(2, ChronoUnit.HOURS);

        // operatingClubId placeholder = the tenant the resolver will resolve to
        // (current when scoped, NO_TENANT when unscoped). Hibernate's @TenantId
        // resolver is authoritative for operating_club_id on INSERT.
        UUID operatingClubPlaceholder =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;

        return AircraftReservation.create(
                operatingClubPlaceholder,
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
