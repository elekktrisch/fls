package ch.alpenflight.server.testsupport;

import ch.alpenflight.planning.domain.PlanningDayAssignmentType;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;

/**
 * Minimal-object factory for {@link PlanningDayAssignmentType} consumed by the
 * S-024 leakage sweep (J-6 T-03). The type has no FK other than its
 * {@code @TenantId} {@code operating_club_id}, so the transient instance the
 * factory hands back persists cleanly under the sweep's tenant — and fails
 * fail-closed at {@code fk_planning_day_assignment_type_operating_club_id}
 * under the NO_TENANT sentinel (V33 realigned that FK to the convention name
 * the sweep reconstructs).
 *
 * <p>The {@code @TenantId} column is NOT set here — Hibernate's resolver fills
 * it on save (same contract as every other sweep builder).
 */
final class PlanningDayAssignmentTypeSweepFactory {

    private PlanningDayAssignmentTypeSweepFactory() {}

    static PlanningDayAssignmentType build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        String unique = Long.toString(System.nanoTime(), 36);
        return PlanningDayAssignmentType.create(
                TenantTestContext.current().orElse(TenantTestContext.NO_TENANT),
                TenantScopedRowBuilders.SWEEP_PREFIX + "PDAT_" + unique);
    }
}
