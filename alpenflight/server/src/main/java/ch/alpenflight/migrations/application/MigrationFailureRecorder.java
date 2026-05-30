package ch.alpenflight.migrations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.IngestFunnelTelemetry;
import ch.alpenflight.migrations.domain.MigrationRun;
import ch.alpenflight.migrations.domain.MigrationRunRepository;
import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import ch.alpenflight.migrations.domain.MigrationUploadState;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records ingest failure trail in a fresh transaction so it survives the
 * main {@code ingestInTransaction} rollback. Lives outside
 * {@link MigrationBundleIngestService} because Spring AOP cannot
 * intercept self-invocation — calling {@code applyFailure} via {@code this}
 * inside the same bean bypasses the {@link Propagation#REQUIRES_NEW}
 * boundary, and the failure writes ride the same doomed transaction.
 */
@Component
public class MigrationFailureRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationFailureRecorder.class);

    private final MigrationUploadRepository uploads;
    private final MigrationRunRepository runs;
    private final IngestFunnelTelemetry telemetry;
    private final AuditTrail audit;
    private final Clock clock;

    public MigrationFailureRecorder(MigrationUploadRepository uploads,
                                    MigrationRunRepository runs,
                                    IngestFunnelTelemetry telemetry,
                                    AuditTrail audit,
                                    Clock clock) {
        this.uploads = uploads;
        this.runs = runs;
        this.telemetry = telemetry;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID uploadId,
                              @Nullable UUID runId,
                              BundleIngestErrorCode errorCode,
                              String detail) {
        try {
            uploads.findById(uploadId).ifPresent(reloaded -> {
                if (reloaded.getState() == MigrationUploadState.AWAITING_UPLOAD) {
                    reloaded.markFailed(clock);
                    uploads.save(reloaded);
                }
            });
            if (runId != null) {
                runs.findById(runId).ifPresent(reloadedRun -> {
                    if (!reloadedRun.getState().isTerminal()) {
                        reloadedRun.markFailed(errorCode.name(), detail, clock);
                        runs.save(reloadedRun);
                    }
                });
            }
            audit.record(AuditAction.MIGRATION_INGEST_FAILED,
                    AuditedTarget.created(
                            MigrationIngestAuditSnapshot.AUDIT_ENTITY_TYPE,
                            uploadId,
                            MigrationIngestAuditSnapshot.failed(uploadId, errorCode, "ingest")));
            telemetry.ingestFailed(uploadId, errorCode, clock.instant());
        } catch (RuntimeException secondaryFailure) {
            LOG.error("Failed to record ingest failure for upload {} (errorCode={}); main rollback proceeds",
                    uploadId, errorCode, secondaryFailure);
        }
    }
}
