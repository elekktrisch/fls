package ch.alpenflight.migrations.domain;

import java.time.Instant;
import java.util.UUID;

public interface IngestFunnelTelemetry {

    void uploadStarted(UUID uploadId, Instant occurredAt);

    void ingestStarted(UUID uploadId, Instant occurredAt);

    void ingestCompleted(UUID uploadId, int clubCount, Instant occurredAt);

    void ingestFailed(UUID uploadId, BundleIngestErrorCode errorCode, Instant occurredAt);
}
