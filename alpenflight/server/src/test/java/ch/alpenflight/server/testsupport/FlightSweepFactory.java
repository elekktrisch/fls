package ch.alpenflight.server.testsupport;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal-object factory for {@link Flight} consumed by the S-024 leakage
 * sweep. Looks up the {@code NOT_PROCESSED} reference row and seeds a
 * minimal Aircraft (Aircraft is cross-tenant per S-058 reverting S-159, so
 * the aircraft row can sit under any club — we use the sweep's tenant when
 * one is set, otherwise the V5 seed club). Air-state is computed (S-060),
 * so no seed lookup needed for that surface.
 */
final class FlightSweepFactory {

    private static final UUID FALLBACK_MANAGING_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private FlightSweepFactory() {}

    static Flight build(SweepFixtureContext ctx) {
        UUID processStateId = firstIdByCode(ctx, "t_flight_process_state", "NOT_PROCESSED");
        UUID currentTenant = TenantTestContext.current().orElse(null);
        // Aircraft is cross-tenant; seed under the current tenant if one is
        // bound, otherwise fall back to the V5 seed club so the aircraft FK
        // resolves even under NO_TENANT. The Flight's own operating_club_id
        // FK (the @TenantId on Flight) is the one that fails fail-closed
        // under NO_TENANT — that's the sweep's negative-path assertion.
        UUID aircraftManager = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? FALLBACK_MANAGING_CLUB
                : currentTenant;
        UUID aircraftId = seedAircraftForTenant(ctx, aircraftManager);
        return Flight.createGlider(aircraftId, processStateId, emptyOps());
    }

    private static UUID seedAircraftForTenant(SweepFixtureContext ctx, UUID managingClub) {
        UUID aircraftTypeId = firstId(ctx, "t_aircraft_type");
        UUID aircraftId = UUID.randomUUID();
        String stamp = Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        if (stamp.length() > 8) {
            stamp = stamp.substring(stamp.length() - 8);
        }
        String immatriculation = "HB-S" + stamp
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

    private static UUID firstId(SweepFixtureContext ctx, String table) {
        UUID id = ctx.jdbc().queryForObject("SELECT id FROM " + table + " LIMIT 1", UUID.class);
        if (id == null) {
            throw new IllegalStateException("No row in " + table);
        }
        return id;
    }

    private static UUID firstIdByCode(SweepFixtureContext ctx, String table, String code) {
        UUID id = ctx.jdbc().queryForObject(
                "SELECT id FROM " + table + " WHERE code = ?", UUID.class, code);
        if (id == null) {
            throw new IllegalStateException(
                    "No row in " + table + " with code=" + code);
        }
        return id;
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
