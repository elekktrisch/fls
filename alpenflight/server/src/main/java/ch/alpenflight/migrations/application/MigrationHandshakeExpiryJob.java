package ch.alpenflight.migrations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.migrations.domain.HandshakeFunnelTelemetry;
import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MigrationHandshakeExpiryJob {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationHandshakeExpiryJob.class);

    private final MigrationUploadRepository repository;
    private final HandshakeFunnelTelemetry telemetry;
    private final AuditTrail audit;
    private final Clock clock;

    public MigrationHandshakeExpiryJob(MigrationUploadRepository repository,
                                       HandshakeFunnelTelemetry telemetry,
                                       AuditTrail audit,
                                       Clock clock) {
        this.repository = repository;
        this.telemetry = telemetry;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 * * * *")
    @UnscopedScheduledJob
    @Transactional
    public void sweep() {
        runOnce();
    }

    @Transactional
    public int runOnce() {
        List<MigrationUpload> expired = repository.findExpired(clock.instant());
        if (expired.isEmpty()) {
            return 0;
        }
        for (MigrationUpload row : expired) {
            UUID rowId = row.getRawId();
            MigrationUploadAuditSnapshot before = MigrationUploadAuditSnapshot.inFlight(
                    rowId, row.getState(), row.getExpiresAt(),
                    row.getPrivateKeyCiphertextLength());
            row.markExpired(clock);
            repository.save(row);
            audit.record(AuditAction.MIGRATION_HANDSHAKE_EXPIRED,
                    AuditedTarget.updated(MigrationUploadAuditSnapshot.AUDIT_ENTITY_TYPE, rowId, before,
                            MigrationUploadAuditSnapshot.wiped(
                                    rowId, row.getState(), row.getExpiresAt())));
            telemetry.expired(rowId, clock.instant());
        }
        repository.flush();
        LOG.info("MigrationHandshakeExpiryJob swept {} expired handshakes", expired.size());
        return expired.size();
    }
}
