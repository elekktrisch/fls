package ch.alpenflight.audit.domain;

import ch.alpenflight.platform.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_mutation_audit_event")
public class MutationAuditEvent {

    @Id
    @UuidV7
    private @Nullable UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private @Nullable Instant occurredAt;

    @Column(name = "actor_user_id", updatable = false)
    private @Nullable UUID actorUserId;

    @Column(name = "actor_keycloak_sub", updatable = false, columnDefinition = "text")
    private @Nullable String actorKeycloakSub;

    @TenantId
    @Column(name = "tenant_club_id", updatable = false)
    private @Nullable UUID tenantClubId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false, length = 32)
    private @Nullable AuditAction action;

    @Column(name = "target_entity_type", nullable = false, updatable = false, length = 64)
    private @Nullable String targetEntityType;

    @Column(name = "target_entity_id", updatable = false)
    private @Nullable UUID targetEntityId;

    @Column(name = "request_id", updatable = false, length = 64)
    private @Nullable String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false, columnDefinition = "jsonb")
    private @Nullable String beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false, columnDefinition = "jsonb")
    private @Nullable String afterState;

    @Column(name = "failed", nullable = false, updatable = false)
    private boolean failed;

    @Column(name = "system_actor", nullable = false, updatable = false)
    private boolean systemActor;

    @Column(name = "http_status", updatable = false)
    private @Nullable Short httpStatus;

    @Column(name = "failure_reason", updatable = false)
    private @Nullable String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, updatable = false, length = 32)
    private @Nullable AuditActorKind actorKind;

    @AuditRedact
    @Column(name = "legacy_actor_user_id", updatable = false, columnDefinition = "text")
    private @Nullable String legacyActorUserId;

    @Column(name = "legacy_int_id", updatable = false)
    private @Nullable Long legacyIntId;

    @AuditRedact
    @Column(name = "legacy_target_record_id", updatable = false, columnDefinition = "text")
    private @Nullable String legacyTargetRecordId;

    @Column(name = "legacy_orphan_actor_id", updatable = false)
    private @Nullable UUID legacyOrphanActorId;

    protected MutationAuditEvent() {
    }

    private MutationAuditEvent(Builder b) {
        this.occurredAt = b.occurredAt;
        this.actorUserId = b.actorUserId;
        this.actorKeycloakSub = b.actorKeycloakSub;
        this.tenantClubId = b.tenantClubId;
        this.action = b.action;
        this.targetEntityType = b.targetEntityType;
        this.targetEntityId = b.targetEntityId;
        this.requestId = b.requestId;
        this.beforeState = b.beforeState;
        this.afterState = b.afterState;
        this.failed = b.failed;
        this.systemActor = b.systemActor;
        this.httpStatus = b.httpStatus;
        this.failureReason = b.failureReason;
        this.actorKind = b.actorKind;
        this.legacyActorUserId = b.legacyActorUserId;
        this.legacyIntId = b.legacyIntId;
        this.legacyTargetRecordId = b.legacyTargetRecordId;
        this.legacyOrphanActorId = b.legacyOrphanActorId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() {
        return Objects.requireNonNull(id, "id is non-null on loaded rows");
    }

    public Instant getOccurredAt() {
        return Objects.requireNonNull(occurredAt, "occurredAt is non-null on loaded rows");
    }

    public @Nullable UUID getActorUserId() {
        return actorUserId;
    }

    public @Nullable String getActorKeycloakSub() {
        return actorKeycloakSub;
    }

    public @Nullable UUID getTenantClubId() {
        return tenantClubId;
    }

    public AuditAction getAction() {
        return Objects.requireNonNull(action, "action is non-null on loaded rows");
    }

    public String getTargetEntityType() {
        return Objects.requireNonNull(targetEntityType, "targetEntityType is non-null on loaded rows");
    }

    public @Nullable UUID getTargetEntityId() {
        return targetEntityId;
    }

    public @Nullable String getRequestId() {
        return requestId;
    }

    public @Nullable String getBeforeState() {
        return beforeState;
    }

    public @Nullable String getAfterState() {
        return afterState;
    }

    public boolean isFailed() {
        return failed;
    }

    public boolean isSystemActor() {
        return systemActor;
    }

    public @Nullable Short getHttpStatus() {
        return httpStatus;
    }

    public @Nullable String getFailureReason() {
        return failureReason;
    }

    public AuditActorKind getActorKind() {
        return Objects.requireNonNull(actorKind, "actorKind is non-null on loaded rows");
    }

    public @Nullable String getLegacyActorUserId() {
        return legacyActorUserId;
    }

    public @Nullable Long getLegacyIntId() {
        return legacyIntId;
    }

    public @Nullable String getLegacyTargetRecordId() {
        return legacyTargetRecordId;
    }

    public @Nullable UUID getLegacyOrphanActorId() {
        return legacyOrphanActorId;
    }

    public static final class Builder {
        private @Nullable Instant occurredAt;
        private @Nullable UUID actorUserId;
        private @Nullable String actorKeycloakSub;
        private @Nullable UUID tenantClubId;
        private @Nullable AuditAction action;
        private @Nullable String targetEntityType;
        private @Nullable UUID targetEntityId;
        private @Nullable String requestId;
        private @Nullable String beforeState;
        private @Nullable String afterState;
        private boolean failed;
        private boolean systemActor;
        private @Nullable Short httpStatus;
        private @Nullable String failureReason;
        private AuditActorKind actorKind = AuditActorKind.NORMAL;
        private @Nullable String legacyActorUserId;
        private @Nullable Long legacyIntId;
        private @Nullable String legacyTargetRecordId;
        private @Nullable UUID legacyOrphanActorId;

        private Builder() {}

        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Builder actorUserId(@Nullable UUID v) { this.actorUserId = v; return this; }
        public Builder actorKeycloakSub(@Nullable String v) { this.actorKeycloakSub = v; return this; }
        public Builder tenantClubId(@Nullable UUID v) { this.tenantClubId = v; return this; }
        public Builder action(AuditAction v) { this.action = v; return this; }
        public Builder targetEntityType(String v) { this.targetEntityType = v; return this; }
        public Builder targetEntityId(@Nullable UUID v) { this.targetEntityId = v; return this; }
        public Builder requestId(@Nullable String v) { this.requestId = v; return this; }
        public Builder beforeState(@Nullable String v) { this.beforeState = v; return this; }
        public Builder afterState(@Nullable String v) { this.afterState = v; return this; }
        public Builder failed(boolean v) { this.failed = v; return this; }
        public Builder systemActor(boolean v) { this.systemActor = v; return this; }
        public Builder httpStatus(@Nullable Short v) { this.httpStatus = v; return this; }
        public Builder failureReason(@Nullable String v) { this.failureReason = v; return this; }
        public Builder actorKind(AuditActorKind v) { this.actorKind = v; return this; }
        public Builder legacyActorUserId(@Nullable String v) { this.legacyActorUserId = v; return this; }
        public Builder legacyIntId(@Nullable Long v) { this.legacyIntId = v; return this; }
        public Builder legacyTargetRecordId(@Nullable String v) { this.legacyTargetRecordId = v; return this; }
        public Builder legacyOrphanActorId(@Nullable UUID v) { this.legacyOrphanActorId = v; return this; }

        public MutationAuditEvent build() {
            if (action == null) {
                throw new IllegalStateException("action is required");
            }
            if (targetEntityType == null || targetEntityType.isBlank()) {
                throw new IllegalStateException("targetEntityType is required");
            }
            if (occurredAt == null) {
                occurredAt = Instant.now();
            }
            return new MutationAuditEvent(this);
        }
    }
}
