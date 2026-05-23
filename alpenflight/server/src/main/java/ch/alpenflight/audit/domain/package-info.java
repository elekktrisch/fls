/**
 * Audit aggregate, port, and shared types.
 *
 * <ul>
 *   <li>{@link ch.alpenflight.audit.domain.MutationAuditEvent} — the entity
 *       persisted to {@code mutation_audit_event}. Carries the
 *       {@code @TenantId} discriminator on {@code tenantClubId}, so the
 *       S-024 leakage sweep covers it automatically and the S-056 list
 *       endpoint inherits per-tenant filtering for free.</li>
 *   <li>{@link ch.alpenflight.audit.domain.AuditTrail} — the port every
 *       service module calls to emit an event. The implementation in
 *       {@code audit.application} publishes a transactional Spring event
 *       that an {@code AFTER_COMMIT} listener writes in its own
 *       {@code REQUIRES_NEW} transaction.</li>
 *   <li>{@link ch.alpenflight.audit.domain.AuditAction},
 *       {@link ch.alpenflight.audit.domain.AuditedTarget},
 *       {@link ch.alpenflight.audit.domain.AuditedBy},
 *       {@link ch.alpenflight.audit.domain.AuditRedact} — the surface
 *       annotations + value types other modules consume.</li>
 * </ul>
 *
 * <p>Allowed dependencies (per ADR 0023 / {@code LayeringRulesTest}): JDK,
 * JPA annotations, JSpecify nullability markers. Forbidden: Spring web,
 * Spring stereotypes, Jackson.
 */
@NullMarked
package ch.alpenflight.audit.domain;

import org.jspecify.annotations.NullMarked;
