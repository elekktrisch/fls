package ch.alpenflight.migrations.application;

import ch.alpenflight.migrations.domain.HandshakeFunnelTelemetry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Funnel-telemetry adapter. Matches the structured-log convention used
 * by {@code DeploymentProvisioningService.LOG.info("funnel event=...")};
 * S-147 will swap call sites to the structured-logging pipeline.
 *
 * <p>Payload pinned to {@code uploadId} + {@code occurredAt} only.
 * NEVER the user id, the Keycloak sub, or the PEM — the trail audit row
 * carries forensic identity, not the funnel.
 */
@Component
class LoggingHandshakeFunnelTelemetry implements HandshakeFunnelTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingHandshakeFunnelTelemetry.class);

    @Override
    public void issued(UUID uploadId, Instant occurredAt) {
        LOG.info("funnel event=migration.handshake_issued uploadId={} occurredAt={}",
                uploadId, occurredAt);
    }

    @Override
    public void superseded(UUID uploadId, Instant occurredAt) {
        LOG.info("funnel event=migration.handshake_superseded uploadId={} occurredAt={}",
                uploadId, occurredAt);
    }

    @Override
    public void expired(UUID uploadId, Instant occurredAt) {
        LOG.info("funnel event=migration.handshake_expired uploadId={} occurredAt={}",
                uploadId, occurredAt);
    }
}
