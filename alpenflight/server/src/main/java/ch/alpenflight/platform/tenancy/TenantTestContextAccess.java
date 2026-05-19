package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Production-classpath hook the resolver consults as the first step of its
 * precedence chain. Production code MUST NEVER call {@link #set(UUID)} — the
 * carrier stays empty and the resolver falls through to its security-context
 * branches.
 *
 * <p>Exists because Maven test-scope hides the S-015 {@code TenantTestContext}
 * from {@code src/main/java}; the resolver in main needs a reachable symbol
 * to consult during tests that override the JWT-based path. The {@code mutate}
 * surface is package-private + the {@code TestSupportPackageBoundaryTest}
 * forbids {@code src/main} from referencing the test-support package, so the
 * only legitimate caller of {@link #set(UUID)} is the test-side
 * {@code TenantTestContext} (which delegates here).
 */
public final class TenantTestContextAccess {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantTestContextAccess() {}

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
