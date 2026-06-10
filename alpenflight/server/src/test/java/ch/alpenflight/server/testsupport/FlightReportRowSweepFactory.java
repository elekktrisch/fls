package ch.alpenflight.server.testsupport;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightReportDecorations;
import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Minimal-object factory for {@link FlightReportRow} consumed by the S-024
 * leakage sweep (J-7 RM-1). The read-model row's PK doubles as an FK to
 * {@code t_flight}, so the factory seeds a minimal flight row (plus its
 * cross-tenant aircraft, mirroring {@link FlightSweepFactory}) under the
 * sweep's current tenant — or the V5 fallback club when unscoped — so the
 * ONLY FK unsatisfiable under {@code NO_TENANT} is the {@code @TenantId}
 * {@code operating_club_id}, tripping the sweep's fail-closed write at
 * {@code fk_flight_report_row_operating_club_id}.
 *
 * <p>The row itself is built through the PRODUCTION projection factory
 * ({@link FlightReportRow#project}) over an in-memory Flight aggregate whose
 * id is reflection-pinned to the JDBC-seeded flight (ADR 0027 §3 — id
 * assignment is deliberately unreachable through the production surface).
 * Decorations resolve to null (no decorating rows needed for the sweep).
 */
final class FlightReportRowSweepFactory {

    private static final UUID FALLBACK_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    /** Null-object decorations — the sweep row needs no display columns. */
    private static final FlightReportDecorations NO_DECORATIONS = new FlightReportDecorations() {
        @Override public @Nullable String immatriculation(@Nullable UUID aircraftId) {
            return null;
        }

        @Override public @Nullable String personName(@Nullable UUID personId) {
            return null;
        }

        @Override public @Nullable FlightTypeDecoration flightType(@Nullable UUID flightTypeId) {
            return null;
        }

        @Override public @Nullable String locationName(@Nullable UUID locationId) {
            return null;
        }

        @Override public @Nullable String startTypeCode(@Nullable UUID startTypeId) {
            return null;
        }
    };

    private FlightReportRowSweepFactory() {}

    static FlightReportRow build(SweepFixtureContext ctx) {
        UUID currentTenant = TenantTestContext.current().orElse(null);
        UUID fkClub = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? FALLBACK_CLUB
                : currentTenant;
        UUID processStateId = firstIdByCode(ctx, "t_flight_process_state", "NOT_PROCESSED");
        UUID aircraftId = seedAircraft(ctx, fkClub);
        UUID flightId = seedFlight(ctx, fkClub, aircraftId, processStateId);

        Flight flight = Flight.createGlider(aircraftId, processStateId, emptyOps());
        setId(flight, flightId);
        UUID operatingClubPlaceholder =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;
        return FlightReportRow.project(flight, null, null, NO_DECORATIONS,
                operatingClubPlaceholder);
    }

    private static UUID seedFlight(SweepFixtureContext ctx, UUID clubId, UUID aircraftId,
                                   UUID processStateId) {
        UUID flightId = UUID.randomUUID();
        ctx.jdbc().update("""
                INSERT INTO t_flight (id, operating_club_id, aircraft_id,
                                    process_state_id, flight_aircraft_type_id)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 1)
                """,
                flightId.toString(), clubId.toString(), aircraftId.toString(),
                processStateId.toString());
        return flightId;
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

    /** Pins the aggregate id to the JDBC-seeded flight row (test-side reflection, ADR 0027 §3). */
    private static void setId(Flight flight, UUID id) {
        try {
            Field field = Flight.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(flight, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot pin Flight.id for the sweep fixture", e);
        }
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
            throw new IllegalStateException("No row in " + table + " with code=" + code);
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
