package ch.alpenflight.server.testsupport;

import ch.alpenflight.planning.domain.PlanningDay;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Minimal-object factory for {@link PlanningDay} consumed by the S-024 leakage
 * sweep (J-6 T-03). The day carries one non-tenant FK — {@code location_id →
 * t_location} (RESTRICT) — so the factory seeds a Location under the tenant the
 * sweep is currently running as (or the fallback V5 seed club when unscoped)
 * through the production save path, so the FK resolves even under the NO_TENANT
 * sentinel.
 *
 * <p>Because the location FK is satisfied under both the scoped and the
 * fallback club, the ONLY FK left unsatisfiable under NO_TENANT is the
 * {@code @TenantId} {@code operating_club_id} (resolved to the nil UUID, absent
 * from {@code t_club}) — so the sweep's fail-closed write assertion trips at
 * {@code fk_planning_day_operating_club_id} (V33 realigned that FK to the
 * convention name the sweep reconstructs), exactly the resolver-drift threat the
 * assertion guards.
 *
 * <p>The {@code @TenantId} column is NOT set here — Hibernate's resolver fills
 * it on save; {@code PlanningDay.create} takes a placeholder club only to pass
 * its own non-null guard (the resolver is authoritative on INSERT).
 */
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
