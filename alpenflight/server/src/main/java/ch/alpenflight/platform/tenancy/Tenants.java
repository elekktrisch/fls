package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Production-facing escape hatch for code that legitimately needs to operate
 * outside the JWT-driven tenant context (ADR 0008 follow-up). Used by:
 *
 * <ul>
 *   <li><strong>Audit listener + request audit filter</strong> — they push
 *       the captured request-time tenant onto the audit-event write
 *       (see {@code MutationAuditEventListener} / {@code RequestAuditFilter}).</li>
 *   <li><strong>OGN ingestion + cross-tenant scheduled jobs</strong> —
 *       endpoints / runners that write on behalf of many clubs in one pass
 *       (S-023 territory; not wired yet).</li>
 *   <li><strong>Cutover / bulk-import</strong> — S-028+ admin-only operations
 *       that import legacy data on behalf of multiple clubs. Bound to a
 *       sysadmin-only HTTP entry point.</li>
 * </ul>
 *
 * <p>The HTTP-exposed {@code /api/v1/admin/locations/{clubId}} impersonation
 * pattern (S-049c) was removed in S-159 — {@code Tenants.runAs} is no longer
 * wired through to a "act as a club from the outside" surface. Tenant data
 * is acted on by members of that tenant; the seam survives only for the
 * non-HTTP cases above.
 *
 * <p>Internally pushes {@code clubId} into {@link TenantContextCarrier}
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
            throw new IllegalArgumentException("clubId must not be null");
        }
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(clubId)) {
            // Silent fail-closed (reads → empty, writes → FK violation) is too
            // quiet for a deliberate caller. The nil-UUID sentinel is the
            // resolver's "no tenant" marker; rebuking it loud at the entry
            // point catches "forgot to derive clubId from a path variable"
            // bugs before they ship.
            throw new IllegalArgumentException("clubId must not be the NO_TENANT sentinel");
        }
        Optional<UUID> prior = TenantContextCarrier.current();
        @Nullable Object priorRequestHint = RequestTenantHint.recordIfHttp(clubId);
        TenantContextCarrier.set(clubId);
        try {
            return body.get();
        } finally {
            if (prior.isPresent()) {
                TenantContextCarrier.set(prior.get());
            } else {
                TenantContextCarrier.clear();
            }
            RequestTenantHint.restoreIfHttp(priorRequestHint);
        }
    }
}
