package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Production-facing escape hatch for code that legitimately needs to operate
 * outside the JWT-driven tenant context (ADR 0008 follow-up). The two cases
 * the project anticipates:
 *
 * <ul>
 *   <li><strong>SYSTEM_ADMINISTRATOR cross-tenant operations</strong> — fixing
 *       data in a club other than the one the JWT's {@code clubId} claim
 *       asserts (see {@code LocationsAdminController}).</li>
 *   <li><strong>OGN ingestion + cross-tenant scheduled jobs</strong> —
 *       endpoints / runners that write on behalf of many clubs in one pass.</li>
 * </ul>
 *
 * <p>Internally pushes {@code clubId} into {@link TenantTestContextAccess}
 * (the same package-mate carrier the test seam uses); the
 * {@link ClubTenantIdentifierResolver}'s precedence chain reads from this
 * carrier first. The {@code .platform.tenancy} package carve-out in
 * {@code TenantBypassGuardTest} permits this; no other production package may
 * push directly.
 *
 * <p>The helper restores the prior carrier value (or absence) after the
 * supplied body completes — nested {@code runAs} calls compose correctly.
 *
 * <p>Use deliberately and audibly: an unguarded {@code runAs} silently
 * elevates the caller's effective tenant for the duration of the block.
 * Pair with a {@code @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")} or
 * equivalent surface gate.
 */
public final class Tenants {

    private Tenants() {}

    /** Runs {@code body} with {@code clubId} as the effective tenant. */
    public static void runAs(UUID clubId, Runnable body) {
        runAs(clubId, () -> {
            body.run();
            return null;
        });
    }

    /** Runs {@code body} with {@code clubId} as the effective tenant; returns its result. */
    public static <T> T runAs(UUID clubId, Supplier<T> body) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId must not be null — use UnscopedTenantContext for unscoped paths");
        }
        Optional<UUID> prior = TenantTestContextAccess.current();
        TenantTestContextAccess.set(clubId);
        try {
            return body.get();
        } finally {
            if (prior.isPresent()) {
                TenantTestContextAccess.set(prior.get());
            } else {
                TenantTestContextAccess.clear();
            }
        }
    }
}
