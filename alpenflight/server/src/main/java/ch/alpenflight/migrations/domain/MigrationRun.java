package ch.alpenflight.migrations.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_migration_run")
public class MigrationRun {

    private static final Map<MigrationRunState, Set<MigrationRunState>> LEGAL_TRANSITIONS;

    static {
        Map<MigrationRunState, Set<MigrationRunState>> edges = new EnumMap<>(MigrationRunState.class);
        edges.put(MigrationRunState.DECRYPTING,
                EnumSet.of(MigrationRunState.PROVISIONING, MigrationRunState.FAILED));
        edges.put(MigrationRunState.PROVISIONING,
                EnumSet.of(MigrationRunState.INGESTING, MigrationRunState.FAILED));
        edges.put(MigrationRunState.INGESTING,
                EnumSet.of(MigrationRunState.COMPLETING, MigrationRunState.FAILED));
        edges.put(MigrationRunState.COMPLETING,
                EnumSet.of(MigrationRunState.COMPLETED, MigrationRunState.FAILED));
        LEGAL_TRANSITIONS = Collections.unmodifiableMap(edges);
    }

    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "upload_id", nullable = false, updatable = false)
    private UUID uploadId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private MigrationRunState state;

    @Column(name = "current_entity", length = 64)
    private @Nullable String currentEntity;

    @Column(name = "current_club_id")
    private @Nullable UUID currentClubId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Column(name = "deployment_id")
    private @Nullable UUID deploymentId;

    @Column(name = "error_code", length = 64)
    private @Nullable String errorCode;

    @AuditRedact
    @Column(name = "error_detail", columnDefinition = "text")
    private @Nullable String errorDetail;

    @AuditRedact
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false, columnDefinition = "jsonb")
    private String warningsJson;

    protected MigrationRun() {
        this.id = new UUID(0, 0);
        this.uploadId = new UUID(0, 0);
        this.state = MigrationRunState.DECRYPTING;
        this.startedAt = Instant.EPOCH;
        this.warningsJson = "[]";
    }

    public static MigrationRun start(UUID id, UUID uploadId, Clock clock) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(clock, "clock");
        MigrationRun run = new MigrationRun();
        run.id = id;
        run.uploadId = uploadId;
        run.state = MigrationRunState.DECRYPTING;
        run.startedAt = clock.instant();
        run.warningsJson = "[]";
        return run;
    }

    public void transitionTo(MigrationRunState target) {
        if (state.isTerminal()) {
            throw new IllegalRunStateException(
                    "Cannot transition from terminal state " + state + " to " + target);
        }
        Set<MigrationRunState> legal = LEGAL_TRANSITIONS.getOrDefault(state, Set.of());
        if (!legal.contains(target)) {
            throw new IllegalRunStateException(
                    "No FSM edge from " + state + " to " + target);
        }
        state = target;
    }

    public void noteCurrent(String entityType, @Nullable UUID clubId) {
        if (state != MigrationRunState.INGESTING) {
            throw new IllegalRunStateException(
                    "noteCurrent only valid in INGESTING, was " + state);
        }
        this.currentEntity = entityType;
        this.currentClubId = clubId;
    }

    public void attachDeployment(UUID deploymentId) {
        if (state != MigrationRunState.PROVISIONING) {
            throw new IllegalRunStateException(
                    "attachDeployment only valid in PROVISIONING, was " + state);
        }
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
    }

    public void markCompleted(Clock clock) {
        transitionTo(MigrationRunState.COMPLETED);
        this.completedAt = clock.instant();
        this.currentEntity = null;
        this.currentClubId = null;
    }

    public void markFailed(String errorCode, @Nullable String errorDetail, Clock clock) {
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        transitionTo(MigrationRunState.FAILED);
        this.errorCode = errorCode;
        this.errorDetail = errorDetail;
        this.completedAt = clock.instant();
    }

    public void replaceWarningsJson(String json) {
        if (json == null || json.isBlank()) {
            this.warningsJson = "[]";
        } else {
            this.warningsJson = json;
        }
    }

    public String getWarningsJson() {
        return warningsJson == null || warningsJson.isBlank() ? "[]" : warningsJson;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUploadId() {
        return uploadId;
    }

    public MigrationRunState getState() {
        return state;
    }

    public @Nullable String getCurrentEntity() {
        return currentEntity;
    }

    public @Nullable UUID getCurrentClubId() {
        return currentClubId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
    }

    public @Nullable UUID getDeploymentId() {
        return deploymentId;
    }

    public @Nullable String getErrorCode() {
        return errorCode;
    }

    public @Nullable String getErrorDetail() {
        return errorDetail;
    }
}
