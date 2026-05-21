package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Production-classpath ThreadLocal carrier the resolver consults as the first
 * step of its precedence chain. Two legitimate callers, both package-mates:
 *
 * <ul>
 *   <li>{@link Tenants}{@code #runAs(...)} — the production escape hatch for
 *       SYSTEM_ADMINISTRATOR cross-tenant operations + cross-tenant scheduled
 *       jobs (ADR 0008 follow-up).</li>
 *   <li>The test-side {@code TenantTestContext} (in {@code src/test/java/.../testsupport})
 *       — delegates here because Maven test-scope hides it from {@code src/main}.</li>
 * </ul>
 *
 * <p>Other production code MUST NEVER call {@link #set(UUID)}: an unguarded
 * caller could bypass the JWT-driven resolver branch and silently set the
 * effective tenant. {@code TenantBypassGuardTest} enforces this with an
 * ArchUnit rule that carves out the {@code platform.tenancy} package only.
 */
public final class TenantContextCarrier {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContextCarrier() {}

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
