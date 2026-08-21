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

@Component
class MutationAuditEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(MutationAuditEventListener.class);

    private final MutationAuditEventRepository repository;
    private final PiiRedactor redactor;
    private final JdbcTemplate jdbc;
    private final ClubTenantIdentifierResolver tenantResolver;

    MutationAuditEventListener(MutationAuditEventRepository repository,
                               PiiRedactor redactor,
                               JdbcTemplate jdbc,
                               ClubTenantIdentifierResolver tenantResolver) {
        this.repository = repository;
        this.redactor = redactor;
        this.jdbc = jdbc;
        this.tenantResolver = tenantResolver;
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
                writeUnscopedRow(request);
            } else if (tenant == null || ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
                UUID resolved = tenantResolver.resolveCurrentTenantIdentifier();
                if (resolved == null || ClubTenantIdentifierResolver.NO_TENANT.equals(resolved)) {
                    writeUnscopedRow(request);
                } else {
                    MutationAuditEvent row = build(request);
                    Tenants.runAs(resolved, () -> repository.append(row));
                }
            } else {
                MutationAuditEvent row = build(request);
                Tenants.runAs(tenant, () -> repository.append(row));
            }
        } catch (RuntimeException e) {
            LOG.error("audit-listener failed to persist row action={} target={} requestId={}",
                    request.action(), request.target().entityType(), request.requestId(), e);
        }
    }

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
                        + "before_state, after_state, failed, system_actor, actor_kind, client_ip, "
                        + "http_status, failure_reason) "
                        + "VALUES (?::uuid, ?, ?::uuid, ?, NULL, ?, ?, ?::uuid, ?, ?::jsonb, ?::jsonb, "
                        + "?, ?, ?, ?, ?, ?)",
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
                request.actorKind().name(),
                request.clientIp(),
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
                .actorKind(request.actorKind())
                .clientIp(request.clientIp())
                .httpStatus(statusShort)
                .failureReason(request.failureReason())
                .build();
    }

    record SyntheticFailedMutation(MutationAuditRequest request) {}
}
