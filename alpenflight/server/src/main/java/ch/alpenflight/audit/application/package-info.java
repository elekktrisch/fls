/**
 * Audit application layer — {@link
 * ch.alpenflight.audit.application.AuditTrailService} (the
 * {@link ch.alpenflight.audit.domain.AuditTrail} adapter), the
 * {@code @TransactionalEventListener AFTER_COMMIT REQUIRES_NEW} writer,
 * the default-deny snapshot serializer, the redaction policy loader, the
 * JWT-sub → user-id resolver, and Caffeine cache.
 *
 * <p>Per ADR 0023 this layer depends on the domain port + adapters in
 * {@code platform.*}; never on {@code audit.web} or {@code audit.infra}
 * implementation types.
 */
@NullMarked
package ch.alpenflight.audit.application;

import org.jspecify.annotations.NullMarked;
