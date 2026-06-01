package ch.alpenflight.migrations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Funnel-telemetry signals for the bundle-ingest pipeline (S-141).
 * Mirrors the handshake-side {@link HandshakeFunnelTelemetry}; S-147 swaps
 * the default logging adapter for the structured-logging pipeline without
 * touching call sites.
 *
 * <p>Payload contract: ids + bounded enum codes + counts. NEVER user id,
 * sizes, display names, or PII — the funnel is a conversion-rate signal
 * (the audit trail covers the forensic ledger, with redaction).
 */
public interface IngestFunnelTelemetry {

    void uploadStarted(UUID uploadId, Instant occurredAt);

    void ingestStarted(UUID uploadId, Instant occurredAt);

    void ingestCompleted(UUID uploadId, int clubCount, Instant occurredAt);

    void ingestFailed(UUID uploadId, BundleIngestErrorCode errorCode, Instant occurredAt);
}
