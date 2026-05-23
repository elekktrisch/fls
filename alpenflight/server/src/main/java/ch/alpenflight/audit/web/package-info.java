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
 *       synthetic {@code failed=true} audit row whenever a mutating
 *       {@code /api/v1/**} response is non-2xx (excluding 401/403 — those
 *       are Actuator's auth-event surface). Covers the gap a rolled-back
 *       transaction creates by losing its AFTER_COMMIT-only success row,
 *       and 4xx rejected-before-controller paths. Cross-tenant admin
 *       paths bind the row to the target tenant via
 *       {@link ch.alpenflight.platform.tenancy.RequestTenantHint}.</li>
 *   <li>{@link ch.alpenflight.audit.web.AuditAdminController} —
 *       {@code GET /api/v1/admin/audit-events} list endpoint, scoped to
 *       the caller's tenant via Hibernate's {@code @TenantId} discriminator
 *       on the entity. Delegates to
 *       {@link ch.alpenflight.audit.application.AuditQueryService} for
 *       the query + DTO mapping. S-056 reads this via the generated TS
 *       client.</li>
 * </ul>
 *
 * <p>Per ADR 0023 this layer depends on {@code audit.application} +
 * {@code audit.domain}, never on {@code audit.infra}.
 */
@NullMarked
package ch.alpenflight.audit.web;

import org.jspecify.annotations.NullMarked;
