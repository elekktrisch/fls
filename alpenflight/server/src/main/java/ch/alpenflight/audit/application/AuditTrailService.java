package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * {@link AuditTrail} adapter — captures actor + tenant context on the
 * publishing thread (where the SecurityContext is still live), then
 * publishes a {@link MutationAuditRequest} for the
 * {@link MutationAuditEventListener} to write after the caller's
 * transaction commits. The listener runs in a separate
 * {@code REQUIRES_NEW} transaction so success rows survive partial rollbacks
 * and so 5xx-inside-a-transaction still leaves the failed-row gap visible
 * to the synthetic-failure filter.
 *
 * <p>{@code systemActor} flag distinguishes "user X acted in tenant T" from
 * "scheduled job acted on tenant T's data" — set when there's no
 * authenticated JWT principal (anonymous public flows + future scheduled
 * jobs). S-056 surfaces the column verbatim.
 */
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
        // Success path → transactional event: AFTER_COMMIT listener fires
        // only if the caller's @Transactional service method commits.
        publisher.publishEvent(build(action, target, false, null, null));
    }

    @Override
    public void recordFailed(AuditAction action,
                             AuditedTarget target,
                             int httpStatus,
                             String failureReason) {
        // Failure path → non-transactional wrapped event: the originating
        // call has either rolled back or been served entirely outside a
        // tx (validation 400, security 401/403, filter-emitted synthetic).
        // Wrapping in SyntheticFailedMutation routes to the plain
        // @EventListener method on MutationAuditEventListener.
        publisher.publishEvent(new MutationAuditEventListener.SyntheticFailedMutation(
                build(action, target, true, httpStatus, failureReason)));
    }

    private MutationAuditRequest build(AuditAction action,
                                       AuditedTarget target,
                                       boolean failed,
                                       @Nullable Integer httpStatus,
                                       @Nullable String failureReason) {
        ActorResolver.Actor actor = actorResolver.resolve();
        boolean systemActor = actor.keycloakSub() == null;
        UUID tenant = resolvedTenant();
        Instant occurredAt = Instant.now(clock);
        // MDC key set by RequestIdFilter; null in non-HTTP origins (jobs,
        // ingestion) until those callers populate the key themselves.
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

    /**
     * Returns the operating tenant for the row. NO_TENANT (nil UUID) signals
     * "no tenant resolved" — for true cross-tenant system events, the row's
     * {@code tenant_club_id} is NULL in DB. The listener translates.
     */
    private @Nullable UUID resolvedTenant() {
        UUID t = TenantContextCarrier.current().orElse(null);
        if (t != null) {
            return t;
        }
        // Resolver would otherwise return NO_TENANT for an unauthenticated
        // / cross-tenant call. Pass through null so the listener leaves the
        // DB column null (forensic "no tenant" semantics).
        return null;
    }

    /** Test-visible NO_TENANT sentinel reference. */
    static final UUID NO_TENANT_SENTINEL = ClubTenantIdentifierResolver.NO_TENANT;
}
