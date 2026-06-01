package ch.alpenflight.migrations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for handshake-related funnel-telemetry signals. The implementation
 * in {@code migrations.application} writes a structured log line; S-147
 * will swap to the structured-logging pipeline without touching call sites.
 *
 * <p>Payload contract: {@code uploadId} + {@code occurredAt} only. NEVER
 * the user id, the Keycloak sub, or the public-key PEM — the funnel is a
 * conversion-rate signal, not a forensic ledger (the audit trail covers
 * that, with redaction).
 */
public interface HandshakeFunnelTelemetry {

    void issued(UUID uploadId, Instant occurredAt);

    void superseded(UUID uploadId, Instant occurredAt);

    void expired(UUID uploadId, Instant occurredAt);
}
