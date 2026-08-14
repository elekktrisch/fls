package ch.alpenflight.server.testsupport;

import ch.alpenflight.planning.domain.PlanningDayAssignmentType;

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
