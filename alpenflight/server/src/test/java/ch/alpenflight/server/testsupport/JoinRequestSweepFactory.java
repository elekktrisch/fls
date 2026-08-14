package ch.alpenflight.server.testsupport;

import ch.alpenflight.joinrequests.domain.JoinRequest;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.util.UUID;

final class JoinRequestSweepFactory {

    private JoinRequestSweepFactory() {}

    @SuppressWarnings("unused")
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
