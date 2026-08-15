package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AuditTrailService implements AuditTrail {

    private final ApplicationEventPublisher publisher;
    private final ActorResolver actorResolver;
    private final Clock clock;

    public AuditTrailService(ApplicationEventPublisher publisher,
                             ActorResolver actorResolver,
                             Clock clock) {
        this.publisher = publisher;
        this.actorResolver = actorResolver;
        this.clock = clock;
    }

    @Override
    public void record(AuditAction action, AuditedTarget target) {
        publisher.publishEvent(build(action, target, false, null, null));
    }

    @Override
    public void recordFailed(AuditAction action,
                             AuditedTarget target,
                             int httpStatus,
                             String failureReason) {
        publisher.publishEvent(new MutationAuditEventListener.SyntheticFailedMutation(
                build(action, target, true, httpStatus, failureReason)));
    }

    private MutationAuditRequest build(AuditAction action,
                                       AuditedTarget target,
                                       boolean failed,
                                       @Nullable Integer httpStatus,
                                       @Nullable String failureReason) {
        ActorResolver.Actor actor = actorResolver.resolve();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean systemActor = !(auth instanceof JwtAuthenticationToken && auth.isAuthenticated());
        UUID tenant = resolvedTenant();
        Instant occurredAt = Instant.now(clock);
        String requestId = MDC.get("requestId");
        return new MutationAuditRequest(
                action,
                target,
                occurredAt,
                actor.userId(),
                actor.keycloakSub(),
                tenant,
                systemActor,
                requestId,
                failed,
                httpStatus,
                failureReason);
    }

    private @Nullable UUID resolvedTenant() {
        UUID t = TenantContextCarrier.current().orElse(null);
        if (t != null) {
            return t;
        }
        return null;
    }
}
