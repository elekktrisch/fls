/**
 * Audit HTTP edges.
 *
 * <ul>
 *   <li>{@link ch.alpenflight.audit.web.RequestIdFilter} — generates a UUID
 *       v7 per request, populates the {@code requestId} MDC key, echoes
 *       {@code X-Request-Id} on the response. S-031 (structured-JSON
 *       logging) will own this filter once it ships; until then S-027
 *       ships the minimal version.</li>
 *   <li>{@link ch.alpenflight.audit.web.RequestAuditFilter} — emits the
 *       synthetic {@code failed=true} audit row when the response is
 *       non-2xx and the underlying service path didn't already record one
 *       (rolled-back transaction loses its AFTER_COMMIT-only success row).
 *       The marker is set via an MDC flag on success — explicit signal
 *       beats inferring from the row count.</li>
 *   <li>{@link ch.alpenflight.audit.web.AuditAdminController} —
 *       {@code GET /api/v1/admin/audit-events} list endpoint, scoped to
 *       the caller's tenant via Hibernate's {@code @TenantId} discriminator
 *       on the entity. S-056 reads this via the generated TS client.</li>
 * </ul>
 *
 * <p>Per ADR 0023 this layer depends on {@code audit.application} +
 * {@code audit.domain}, never on {@code audit.infra}.
 */
@NullMarked
package ch.alpenflight.audit.web;

import org.jspecify.annotations.NullMarked;
