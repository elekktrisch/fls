package ch.alpenflight.server.testsupport;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightReportDecorations;
import ch.alpenflight.flights.domain.FlightReportRow;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

final class FlightReportRowSweepFactory {

    private static final UUID FALLBACK_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

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
