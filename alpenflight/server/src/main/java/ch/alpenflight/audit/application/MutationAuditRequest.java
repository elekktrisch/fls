package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditedTarget;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record MutationAuditRequest(AuditAction action,
                            AuditedTarget target,
                            Instant occurredAt,
                            @Nullable UUID actorUserId,
                            @Nullable String actorKeycloakSub,
                            @Nullable UUID tenantClubId,
                            boolean systemActor,
                            @Nullable String requestId,
                            boolean failed,
                            @Nullable Integer httpStatus,
                            @Nullable String failureReason) {
}
