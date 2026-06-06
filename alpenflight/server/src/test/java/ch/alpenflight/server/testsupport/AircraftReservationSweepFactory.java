package ch.alpenflight.server.testsupport;

import ch.alpenflight.reservations.domain.AircraftReservation;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal-object factory for {@link AircraftReservation} consumed by the S-024
 * leakage sweep (J-5 T-29). The reservation carries five non-tenant FKs that
 * must resolve at INSERT, so the factory seeds them directly via JDBC, keyed to
 * the tenant the sweep is currently running as (mirroring how
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
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

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

        UUID aircraftId = seedAircraft(ctx, fkClub);
        UUID pilotPersonId = seedPerson(ctx);
        UUID locationId = seedLocation(ctx, fkClub);
        UUID reservationTypeId = seedReservationType(ctx, fkClub);

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

    private static UUID seedAircraft(SweepFixtureContext ctx, UUID managingClub) {
        UUID aircraftTypeId = firstId(ctx, "t_aircraft_type");
        UUID aircraftId = UUID.randomUUID();
        String stamp = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        if (stamp.length() > 8) {
            stamp = stamp.substring(stamp.length() - 8);
        }
        String immatriculation = "HB-R" + stamp
                + String.format(Locale.ROOT, "%02d", COUNTER.incrementAndGet() % 100);
        ctx.jdbc().update("""
                INSERT INTO t_aircraft (id, managing_club_id, owner_club_id, aircraft_type_id,
                                      immatriculation, is_towing_or_winch_required,
                                      is_towing_start_allowed, is_winch_start_allowed,
                                      is_towing_aircraft, is_fast_entry_record, nr_of_seats)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?,
                        false, false, false, false, false, 2)
                """,
                aircraftId.toString(),
                managingClub.toString(),
                managingClub.toString(),
                aircraftTypeId.toString(),
                immatriculation);
        return aircraftId;
    }

    private static UUID seedPerson(SweepFixtureContext ctx) {
        UUID personId = UUID.randomUUID();
        String unique = Long.toString(System.nanoTime(), 36);
        ctx.jdbc().update(
                "INSERT INTO t_person (id, lastname, firstname) VALUES (?::uuid, ?, ?)",
                personId.toString(),
                TenantScopedRowBuilders.SWEEP_PREFIX + "PILOT_" + unique,
                "Sweep");
        return personId;
    }

    private static UUID seedLocation(SweepFixtureContext ctx, UUID club) {
        UUID countryId = firstId(ctx, "t_country");
        UUID locationTypeId = firstId(ctx, "t_location_type");
        UUID locationId = UUID.randomUUID();
        String unique = Long.toString(System.nanoTime(), 36);
        ctx.jdbc().update("""
                INSERT INTO t_location (id, club_id, location_name, country_id, location_type_id)
                VALUES (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid)
                """,
                locationId.toString(),
                club.toString(),
                TenantScopedRowBuilders.SWEEP_PREFIX + "LOC_" + unique,
                countryId.toString(),
                locationTypeId.toString());
        return locationId;
    }

    private static UUID seedReservationType(SweepFixtureContext ctx, UUID club) {
        UUID typeId = UUID.randomUUID();
        String unique = Long.toString(System.nanoTime(), 36);
        ctx.jdbc().update("""
                INSERT INTO t_aircraft_reservation_type
                    (id, operating_club_id, reservation_type_name,
                     is_instructor_required, is_maintenance, is_active)
                VALUES (?::uuid, ?::uuid, ?, false, false, true)
                """,
                typeId.toString(),
                club.toString(),
                TenantScopedRowBuilders.SWEEP_PREFIX + "RTYPE_" + unique);
        return typeId;
    }

    private static UUID firstId(SweepFixtureContext ctx, String table) {
        UUID id = ctx.jdbc().queryForObject("SELECT id FROM " + table + " LIMIT 1", UUID.class);
        if (id == null) {
            throw new IllegalStateException(
                    "No row in " + table + " — V3 seed must populate at least one reference row");
        }
        return id;
    }
}
