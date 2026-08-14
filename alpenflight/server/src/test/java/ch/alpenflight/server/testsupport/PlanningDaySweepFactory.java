package ch.alpenflight.server.testsupport;

import ch.alpenflight.planning.domain.PlanningDay;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

final class PlanningDaySweepFactory {

    private static final UUID FALLBACK_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private PlanningDaySweepFactory() {}

    static PlanningDay build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID currentTenant = TenantTestContext.current().orElse(null);
        UUID fkClub = currentTenant == null
                || TenantTestContext.NO_TENANT.equals(currentTenant)
                ? FALLBACK_CLUB
                : currentTenant;

        UUID locationId = ctx.seedLocation(fkClub);
        UUID operatingClubPlaceholder =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;

        return PlanningDay.create(
                operatingClubPlaceholder,
                LocalDate.now(java.time.ZoneOffset.UTC).plus(7, ChronoUnit.DAYS),
                locationId,
                TenantScopedRowBuilders.SWEEP_PREFIX + "PLN");
    }
}
