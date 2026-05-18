package ch.alpenflight.server.testsupport;

import java.util.Optional;
import java.util.UUID;

/**
 * Test-side carrier for the currently-active tenant identifier. A
 * {@code ThreadLocal} holds the UUID so JUnit Jupiter's per-test lifecycle
 * (via {@link TenantContextExtension}) can push a value at the start of a
 * test and clear it at the end without coupling production code to the test
 * surface.
 *
 * <p>S-022 swaps in the real Hibernate {@code CurrentTenantIdentifierResolver}:
 * the resolver consults this carrier first (test seam), then
 * {@code SecurityContextHolder}, then DB fallback, then the {@link #NO_TENANT}
 * sentinel. Until then this class only stores the value; nothing wires it
 * into Hibernate.
 *
 * <p>The {@link #NO_TENANT} sentinel is the nil UUID. S-022's
 * {@code PreInsertEventListener} guards against writes whose
 * {@code @TenantId} column resolves to nil.
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
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantTestContext() {}

    public static void set(UUID clubId) {
        CURRENT.set(clubId);
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Run {@code body} with {@code clubId} active, restoring the prior value
     * (or absence) after the block. Used by tests that need to assert
     * cross-tenant behaviour mid-method — create-as-A, switch-to-B, read,
     * switch-back.
     */
    public static void runAs(UUID clubId, Runnable body) {
        UUID prior = CURRENT.get();
        CURRENT.set(clubId);
        try {
            body.run();
        } finally {
            if (prior == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(prior);
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
