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

/**
 * Per-bundle ingest run (S-141). One row per POST /bundle attempt. The
 * caller drives the FSM via the mutator methods; the schema only stores
 * the structural columns (PK, FK, identity-bearing partial UNIQUE) per
 * ADR 0022 directive 2.
 *
 * <p>The {@code warnings} jsonb column carries a serialized
 * {@link MigrationRunWarning} array; the application-layer
 * {@code MigrationRunWarnings} codec owns the (de)serialization (Jackson
 * is banned in {@code domain/} per ADR 0023).
 */
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

    /**
     * Free-form failure context. May echo a failing legacy row's column —
     * redacted on the audit walk; the application-layer audit emitter
     * uses {@code MigrationIngestAuditSnapshot} which doesn't carry this
     * field at all.
     */
    @AuditRedact
    @Column(name = "error_detail", columnDefinition = "text")
    private @Nullable String errorDetail;

    /** Serialized {@link MigrationRunWarning} array. */
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

    /**
     * Records the entity + Club the ingest pipeline is currently writing.
     * Called once per (entity, club) transition — not per row — to keep
     * the status-poll endpoint cheap.
     */
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

    /**
     * Overwrites the serialized warnings. Callers in the application layer
     * own the (de)serialization through {@code MigrationRunWarnings}; the
     * aggregate keeps Jackson out of the domain package per ADR 0023.
     */
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
