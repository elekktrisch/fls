package ch.alpenflight.server.testsupport;

import ch.alpenflight.joinrequests.domain.JoinRequest;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.util.UUID;

/**
 * Minimal-object factory for {@link JoinRequest} consumed by the S-024 leakage
 * sweep. {@code club_id} is the aggregate's only FK and its {@code @TenantId}
 * discriminator, so it fails fail-closed under the NO_TENANT sentinel
 * ({@code fk_join_request_club_id}) with no reference data to seed (mirrors
 * {@code EmailTemplateSweepFactory}).
 *
 * <p>{@link JoinRequest#submit} requires a non-null club to pass its own guard,
 * but Hibernate's {@code @TenantId} generator rejects a row whose assigned
 * tenant differs from the resolver's current tenant. So the placeholder must BE
 * the current tenant (the resolver value), mirroring {@code PlanningDaySweep-
 * Factory}: under NO_TENANT the placeholder is the nil sentinel, which then
 * fails fail-closed at {@code fk_join_request_club_id}. A fresh KC sub per build
 * keeps the {@code ux_join_request_alive} one-open-per-(sub, club) partial
 * UNIQUE from colliding across the sweep's repeated inserts.
 */
final class JoinRequestSweepFactory {

    private JoinRequestSweepFactory() {}

    @SuppressWarnings("unused") // ctx unused — JoinRequest has no FK reference data to look up.
    static JoinRequest build(SweepFixtureContext ctx) {
        UUID currentTenant = TenantTestContext.current().orElse(TenantTestContext.NO_TENANT);
        String unique = Long.toString(System.nanoTime(), 36);
        return JoinRequest.submit(
                UuidCreator.getTimeOrderedEpoch(),
                UuidCreator.getTimeOrderedEpoch(),
                TenantScopedRowBuilders.SWEEP_PREFIX + unique + "@example.com",
                TenantScopedRowBuilders.SWEEP_PREFIX + "pilot",
                currentTenant,
                null,
                Clock.systemUTC());
    }
}
