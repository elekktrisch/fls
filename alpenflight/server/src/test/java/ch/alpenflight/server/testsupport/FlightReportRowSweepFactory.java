package ch.alpenflight.server.testsupport;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightReportDecorations;
import ch.alpenflight.flights.domain.FlightReportRow;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Minimal-object factory for {@link FlightReportRow} consumed by the S-024
 * leakage sweep (J-7 RM-1). The read-model row's PK doubles as an FK to
 * {@code t_flight}, so the factory seeds a minimal Flight (plus its
 * cross-tenant Aircraft) under the sweep's current tenant — or the V5 fallback
 * club when unscoped — through the production save path, then projects the row
 * off that <em>persisted</em> Flight aggregate (whose minted id satisfies the
 * PK/FK). The ONLY FK unsatisfiable under {@code NO_TENANT} is the
 * {@code @TenantId} {@code operating_club_id}, tripping the sweep's fail-closed
 * write at {@code fk_flight_report_row_operating_club_id}.
 *
 * <p>The row itself is built through the PRODUCTION projection factory
 * ({@link FlightReportRow#project}) over the saved Flight — no reflection-pinned
 * id needed, since the parent Flight's id is minted on save and read back via
 * {@code flight.getId()} (the J-26 T-20 retirement of the JDBC seam).
 * Decorations resolve to null (no decorating rows needed for the sweep).
 */
final class FlightReportRowSweepFactory {

    private static final UUID FALLBACK_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

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
        UUID aircraftId = ctx.seedAircraft(fkClub);
        Flight flight = ctx.seedFlight(fkClub, aircraftId);

        UUID operatingClubPlaceholder =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;
        return FlightReportRow.project(flight, null, null, NO_DECORATIONS,
                operatingClubPlaceholder);
    }
}
