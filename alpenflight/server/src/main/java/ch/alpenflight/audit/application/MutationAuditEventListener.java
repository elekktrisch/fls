package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes the audit row in a separate {@code REQUIRES_NEW} transaction after
 * the publishing transaction commits.
 *
 * <p>Two listener methods cover the two emission paths:
 *
 * <ul>
 *   <li>{@link #onCommittedMutation} — the AFTER_COMMIT path for successful
 *       mutations published by {@link AuditTrailService#record}. Runs only
 *       if the business transaction commits, so a rolled-back business
 *       call (any thrown exception inside the {@code @Transactional}
 *       service method) leaves no success row. The synthetic-failure
 *       filter handles that gap.</li>
 *   <li>{@link #onSyntheticFailure} — plain (non-transactional)
 *       {@link EventListener} for {@code failed=true} rows published by
 *       {@link ch.alpenflight.audit.web.RequestAuditFilter} after the
 *       response is committed. Already runs outside any transaction; the
 *       {@code REQUIRES_NEW} on the method opens its own.</li>
 * </ul>
 *
 * <p>The listener swallows + logs at ERROR on its own failure — an audit
 * write that throws must not roll the business transaction back (already
 * committed in the success path; would mask the original error in the
 * failed path). Operators see the gap in the error log.
 */
@Component
class MutationAuditEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(MutationAuditEventListener.class);

    private final MutationAuditEventRepository repository;
    private final PiiRedactor redactor;

    MutationAuditEventListener(MutationAuditEventRepository repository, PiiRedactor redactor) {
        this.repository = repository;
        this.redactor = redactor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCommittedMutation(MutationAuditRequest request) {
        safeWrite(request);
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSyntheticFailure(SyntheticFailedMutation event) {
        safeWrite(event.request());
    }

    private void safeWrite(MutationAuditRequest request) {
        try {
            MutationAuditEvent row = build(request);
            UUID tenant = request.tenantClubId();
            if (tenant == null || ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
                // Per-row tenancy: cross-tenant system events store NULL.
                // No-op for now — tenant left as null on the entity, the
                // @TenantId resolver returns NO_TENANT, Hibernate writes
                // null because the entity field is null.
                repository.append(row);
            } else {
                // Force the resolver to see this exact tenant — guarantees
                // the @TenantId column matches the captured operating
                // tenant even if the original SecurityContext has been
                // cleared by the time AFTER_COMMIT fires.
                Tenants.runAs(tenant, () -> repository.append(row));
            }
        } catch (RuntimeException e) {
            LOG.error("audit-listener failed to persist row action={} target={} requestId={}",
                    request.action(), request.target().entityType(), request.requestId(), e);
        }
    }

    private MutationAuditEvent build(MutationAuditRequest request) {
        String entityType = request.target().entityType();
        @Nullable String before = redactor.serialize(entityType, request.target().before());
        @Nullable String after = redactor.serialize(entityType, request.target().after());
        Integer status = request.httpStatus();
        Short statusShort = status == null ? null : status.shortValue();
        return MutationAuditEvent.builder()
                .occurredAt(request.occurredAt())
                .actorUserId(request.actorUserId())
                .actorKeycloakSub(request.actorKeycloakSub())
                .tenantClubId(request.tenantClubId())
                .action(request.action())
                .targetEntityType(entityType)
                .targetEntityId(request.target().entityId())
                .requestId(request.requestId())
                .beforeState(before)
                .afterState(after)
                .failed(request.failed())
                .systemActor(request.systemActor())
                .httpStatus(statusShort)
                .failureReason(request.failureReason())
                .build();
    }

    /**
     * Internal event published by {@link ch.alpenflight.audit.web.RequestAuditFilter}
     * for the synthetic-failure path. Wrapping the request keeps the two
     * listener methods distinguishable on type (Spring routes by event
     * class).
     */
    record SyntheticFailedMutation(MutationAuditRequest request) {}
}
