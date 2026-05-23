package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditedTarget;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Internal carrier — what {@link AuditTrailService} publishes for the
 * {@code @TransactionalEventListener} to consume after commit. Captures
 * the calling thread's actor + tenant context at publish time so the
 * listener's {@code REQUIRES_NEW} transaction (which runs after the
 * SecurityContext + MDC may have been cleared) still records the right
 * principal.
 *
 * <p>Visibility deliberately package-private — only the audit module's
 * listener is supposed to consume this. Spring's
 * {@code ApplicationEventPublisher} bypasses Java visibility, but other
 * modules cannot reference the type to construct or subscribe.
 */
record MutationAuditRequest(AuditAction action,
                            AuditedTarget target,
                            Instant occurredAt,
                            @Nullable UUID actorUserId,
                            @Nullable UUID actorKeycloakSub,
                            @Nullable UUID tenantClubId,
                            boolean systemActor,
                            @Nullable String requestId,
                            boolean failed,
                            @Nullable Integer httpStatus,
                            @Nullable String failureReason) {
}
