package ch.alpenflight.server.testsupport;

import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.TenantTestContextAccess;
import java.util.Optional;
import java.util.UUID;

/**
 * Test-side carrier for the currently-active tenant identifier. Delegates
 * to {@link TenantTestContextAccess} (the production-classpath shim the
 * {@link ClubTenantIdentifierResolver} consults as the first step of its
 * precedence chain) — Maven test-scope hides this class from {@code src/main},
 * so the shim is the resolver's reachable hook.
 *
 * <p>S-022 wired the resolver to actually read this carrier: a test that
 * sets a value here also drives the {@code @TenantId} filter Hibernate
 * appends to every tenant-scoped query.
 *
 * <p>The {@link #NO_TENANT} sentinel is the nil UUID. Inserts whose
 * {@code @TenantId} column resolves to the sentinel fail at the
 * {@code fk_<table>_club_id} foreign-key constraint (the nil UUID is
 * absent from {@code club}).
 *
 * <p>Parallel test execution is incompatible with the {@code ThreadLocal}
 * carrier — {@link JunitPlatformConfigTest} pins
 * {@code junit.jupiter.execution.parallel.enabled=false} until the
 * ADR 0021 per-test data-isolation policy is audited across the suite.
 */
public final class TenantTestContext {

    /**
     * Sentinel returned when a test exercises the legitimate unscoped path
     * via {@link #runUnscoped(Runnable)}. Distinct from {@link #current()}'s
     * empty result, which signals "no test has set a tenant at all."
     */
    public static final UUID NO_TENANT = ClubTenantIdentifierResolver.NO_TENANT;

    private TenantTestContext() {}

    public static void set(UUID tenantId) {
        TenantTestContextAccess.set(tenantId);
    }

    public static Optional<UUID> current() {
        return TenantTestContextAccess.current();
    }

    public static void clear() {
        TenantTestContextAccess.clear();
    }

    /**
     * Run {@code body} with {@code tenantId} active, restoring the prior value
     * (or absence) after the block. Used by tests that need to assert
     * cross-tenant behaviour mid-method — create-as-A, switch-to-B, read,
     * switch-back.
     */
    public static void runAs(UUID tenantId, Runnable body) {
        Optional<UUID> prior = TenantTestContextAccess.current();
        TenantTestContextAccess.set(tenantId);
        try {
            body.run();
        } finally {
            if (prior.isPresent()) {
                TenantTestContextAccess.set(prior.get());
            } else {
                TenantTestContextAccess.clear();
            }
        }
    }

    /**
     * Run {@code body} with the {@link #NO_TENANT} sentinel active. Named
     * distinctly from {@link #runAs(UUID, Runnable)} so reviewers see the
     * explicit unscoped intent — "forgot to annotate" never produces this
     * state.
     */
    public static void runUnscoped(Runnable body) {
        runAs(NO_TENANT, body);
    }
}
