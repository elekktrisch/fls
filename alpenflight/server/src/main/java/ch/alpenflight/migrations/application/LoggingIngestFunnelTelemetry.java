package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.IngestFunnelTelemetry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link IngestFunnelTelemetry} adapter — emits one structured
 * log line per event. S-147 will swap this for the structured-logging
 * pipeline.
 */
@Component
class LoggingIngestFunnelTelemetry implements IngestFunnelTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger("funnel.migration.ingest");

    @Override
    public void uploadStarted(UUID uploadId, Instant occurredAt) {
        LOG.info("event=migration.upload_started uploadId={} occurredAt={}", uploadId, occurredAt);
    }

    @Override
    public void ingestStarted(UUID uploadId, Instant occurredAt) {
        LOG.info("event=migration.ingest_started uploadId={} occurredAt={}", uploadId, occurredAt);
    }

    @Override
    public void ingestCompleted(UUID uploadId, int clubCount, Instant occurredAt) {
        LOG.info("event=migration.ingest_completed uploadId={} clubCount={} occurredAt={}",
                uploadId, clubCount, occurredAt);
    }

    @Override
    public void ingestFailed(UUID uploadId, BundleIngestErrorCode errorCode, Instant occurredAt) {
        LOG.info("event=migration.ingest_failed uploadId={} errorCode={} occurredAt={}",
                uploadId, errorCode, occurredAt);
    }
}
