package ch.alpenflight.server.testsupport;

import ch.alpenflight.planning.domain.PlanningDay;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Minimal-object factory for {@link PlanningDay} consumed by the S-024 leakage
 * sweep (J-6 T-03). The day carries one non-tenant FK — {@code location_id →
 * t_location} (RESTRICT) — so the factory seeds a location under the tenant the
 * sweep is currently running as (or the fallback V5 seed club when unscoped) so
 * the FK resolves even under the NO_TENANT sentinel.
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

        UUID locationId = seedLocation(ctx, fkClub);
        UUID operatingClubPlaceholder =
                currentTenant == null ? TenantTestContext.NO_TENANT : currentTenant;

        return PlanningDay.create(
                operatingClubPlaceholder,
                LocalDate.now(java.time.ZoneOffset.UTC).plus(7, ChronoUnit.DAYS),
                locationId,
                TenantScopedRowBuilders.SWEEP_PREFIX + "PLN");
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
                TenantScopedRowBuilders.SWEEP_PREFIX + "PLNLOC_" + unique,
                countryId.toString(),
                locationTypeId.toString());
        return locationId;
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
