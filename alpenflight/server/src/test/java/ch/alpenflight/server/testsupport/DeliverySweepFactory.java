package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.Delivery;

/**
 * Minimal-object factory for {@link Delivery} consumed by the S-024 leakage
 * sweep. The aggregate's FKs ({@code flight_id},
 * {@code recipient_person_id}) are all nullable, so no FK parent needs seeding —
 * the ONLY column the {@code @TenantId} resolver fills is {@code operating_club_id},
 * leaving it the single FK left unsatisfiable under the {@code NO_TENANT} sentinel.
 * The sweep's fail-closed write assertion trips at
 * {@code fk_dlv_operating_club_id}.
 *
 * <p>Read-only iteration: the aggregate has no write factory, so the bare
 * transient is built via {@link DeliveryTestHydrator#bare()} (reflection, never
 * setting {@code operatingClubId} — the resolver owns it).
 */
final class DeliverySweepFactory {

    private DeliverySweepFactory() {}

    static Delivery build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        return DeliveryTestHydrator.bare();
    }
}
