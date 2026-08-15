package ch.alpenflight.migrations.domain;

import java.time.Instant;
import java.util.UUID;

public interface HandshakeFunnelTelemetry {

    void issued(UUID uploadId, Instant occurredAt);

    void superseded(UUID uploadId, Instant occurredAt);

    void expired(UUID uploadId, Instant occurredAt);
}
