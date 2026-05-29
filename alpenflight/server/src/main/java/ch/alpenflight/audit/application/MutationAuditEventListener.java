package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.Tenants;
import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    MutationAuditEventListener(MutationAuditEventRepository repository,
                               PiiRedactor redactor,
                               JdbcTemplate jdbc) {
        this.repository = repository;
        this.redactor = redactor;
        this.jdbc = jdbc;
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
            UUID tenant = request.tenantClubId();
            if (request.systemActor()
                    && (tenant == null || ClubTenantIdentifierResolver.NO_TENANT.equals(tenant))) {
                // True system events (no JWT principal — scheduled jobs,
                // bulk ingestion) store NULL in tenant_club_id. JPA can't
                // write that here: Hibernate's @TenantId resolver would
                // override the null field with NO_TENANT (nil UUID), which
                // fails the t_club FK. JDBC bypasses the discriminator
                // and lets the FK's "NULL ⇒ no parent" rule apply naturally.
                writeUnscopedRow(request);
            } else if (tenant == null || ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
                // Authenticated principal but tenant context isn't populated
                // (e.g. the synthetic-failure path running after the response
                // is committed). Let Hibernate's resolver re-read the
                // SecurityContext to fill the tenant — that captured the
                // user's clubId at request time and is still on the thread.
                MutationAuditEvent row = build(request);
                repository.append(row);
            } else {
                // Force the resolver to see this exact tenant — guarantees
                // the @TenantId column matches the captured operating
                // tenant even if the original SecurityContext has been
                // cleared by the time AFTER_COMMIT fires.
                MutationAuditEvent row = build(request);
                Tenants.runAs(tenant, () -> repository.append(row));
            }
        } catch (RuntimeException e) {
            LOG.error("audit-listener failed to persist row action={} target={} requestId={}",
                    request.action(), request.target().entityType(), request.requestId(), e);
        }
    }

    /**
     * Cross-tenant audit-row write that bypasses Hibernate's @TenantId
     * discriminator. Used for true system events (e.g. S-140 hourly
     * handshake-TTL sweep) where {@code tenant_club_id} is legitimately
     * NULL. The column allows NULL by design; only Hibernate's resolver
     * gets in the way.
     */
    private void writeUnscopedRow(MutationAuditRequest request) {
        String entityType = request.target().entityType();
        @Nullable String before = redactor.serialize(entityType, request.target().before());
        @Nullable String after = redactor.serialize(entityType, request.target().after());
        Integer status = request.httpStatus();
        Short statusShort = status == null ? null : status.shortValue();
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.update(
                "INSERT INTO t_mutation_audit_event ("
                        + "id, occurred_at, actor_user_id, actor_keycloak_sub, tenant_club_id, "
                        + "action, target_entity_type, target_entity_id, request_id, "
                        + "before_state, after_state, failed, system_actor, http_status, failure_reason) "
                        + "VALUES (?::uuid, ?, ?::uuid, ?, NULL, ?, ?, ?::uuid, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)",
                id, java.sql.Timestamp.from(request.occurredAt()),
                request.actorUserId() == null ? null : request.actorUserId().toString(),
                request.actorKeycloakSub(),
                request.action().name(),
                entityType,
                request.target().entityId() == null ? null : request.target().entityId().toString(),
                request.requestId(),
                before,
                after,
                request.failed(),
                request.systemActor(),
                statusShort,
                request.failureReason());
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
