package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditActorKind;
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

    private static final @Nullable String NO_CLIENT_IP_UNTIL_A_FAILED_ROW_NAMES_ITS_CLUB = null;

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
        publisher.publishEvent(
                build(action, target, attributionOfTheCurrentPrincipal(), false, null, null));
    }

    @Override
    public void recordAnonymousPublicSubmission(AuditAction action,
                                                AuditedTarget target,
                                                String clientIp) {
        publisher.publishEvent(build(action, target,
                anonymousPublicAttributionUnlessAPrincipalIsAuthenticated(clientIp),
                false, null, null));
    }

    @Override
    public void recordFailed(AuditAction action,
                             AuditedTarget target,
                             int httpStatus,
                             String failureReason) {
        publisher.publishEvent(new MutationAuditEventListener.SyntheticFailedMutation(
                build(action, target, attributionOfTheCurrentPrincipal(),
                        true, httpStatus, failureReason)));
    }

    @Override
    public void recordFailedHttpRequest(AuditAction action,
                                        AuditedTarget target,
                                        int httpStatus,
                                        String failureReason) {
        publisher.publishEvent(new MutationAuditEventListener.SyntheticFailedMutation(
                build(action, target,
                        anonymousPublicAttributionUnlessAPrincipalIsAuthenticated(
                                NO_CLIENT_IP_UNTIL_A_FAILED_ROW_NAMES_ITS_CLUB),
                        true, httpStatus, failureReason)));
    }

    private MutationAuditRequest build(AuditAction action,
                                       AuditedTarget target,
                                       ActorAttribution attribution,
                                       boolean failed,
                                       @Nullable Integer httpStatus,
                                       @Nullable String failureReason) {
        ActorResolver.Actor actor = actorResolver.resolve();
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
                attribution.systemActor(),
                attribution.kind(),
                attribution.clientIp(),
                requestId,
                failed,
                httpStatus,
                failureReason);
    }

    private static ActorAttribution attributionOfTheCurrentPrincipal() {
        return anAuthenticatedJwtPrincipalIsPresent()
                ? new ActorAttribution(AuditActorKind.NORMAL, false, null)
                : new ActorAttribution(AuditActorKind.SYSTEM, true, null);
    }

    private static ActorAttribution anonymousPublicAttributionUnlessAPrincipalIsAuthenticated(
            @Nullable String clientIp) {
        return anAuthenticatedJwtPrincipalIsPresent()
                ? attributionOfTheCurrentPrincipal()
                : new ActorAttribution(AuditActorKind.ANONYMOUS_PUBLIC, false, clientIp);
    }

    private static boolean anAuthenticatedJwtPrincipalIsPresent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken && auth.isAuthenticated();
    }

    private @Nullable UUID resolvedTenant() {
        return TenantContextCarrier.current().orElse(null);
    }

    private record ActorAttribution(AuditActorKind kind,
                                    boolean systemActor,
                                    @Nullable String clientIp) {
    }
}
