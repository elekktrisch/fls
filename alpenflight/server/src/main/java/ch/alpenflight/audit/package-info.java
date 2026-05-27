/**
 * Mutation audit trail — every successful (and observed-failed) mutating
 * endpoint emits a {@link ch.alpenflight.audit.domain.MutationAuditEvent}
 * row capturing actor, tenant, action, target, and before/after snapshots.
 *
 * <p>Declared as an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} module so every business module ({@code clubs}, {@code locations},
 * future {@code flights}, …) may call
 * {@link ch.alpenflight.audit.domain.AuditTrail#record} and reference the
 * {@link ch.alpenflight.audit.domain.AuditAction} enum +
 * {@link ch.alpenflight.audit.domain.AuditedTarget} record without going
 * through a named interface. The OPEN type matches {@code platform.*} and
 * {@code referencedata.*} — shared kernel surfaces every module reads.
 *
 * <p>The mechanism (chosen at refine over AOP-only): mutating services call
 * {@code auditTrail.record(...)}; an {@code @TransactionalEventListener
 * (phase = AFTER_COMMIT)} listener writes the row in a separate
 * {@code REQUIRES_NEW} transaction so the success-path row rides its own tx
 * and a rolled-back business transaction doesn't drop the audit. A
 * {@link ch.alpenflight.audit.web.RequestAuditFilter} after Spring Security
 * emits a synthetic {@code failed=true} row when the response is non-2xx and
 * no event was already recorded — covers thrown exceptions + 4xx returns.
 *
 * <p>Naming: {@code MutationAuditEvent} (not {@code AuditEvent}) so Spring
 * Boot Actuator's {@code org.springframework.boot.actuate.audit.AuditEvent}
 * (auth events) cannot collide. The DB table is {@code t_mutation_audit_event}.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
