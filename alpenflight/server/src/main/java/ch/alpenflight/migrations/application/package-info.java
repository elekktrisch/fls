/**
 * Handshake use-case service, hourly TTL expiry job, audit-snapshot
 * record, structured-logging funnel-telemetry adapter.
 *
 * <p>Per ADR 0023 this layer orchestrates domain calls + outbound port
 * adapters; no domain logic lives here (the state-machine guards stay
 * on {@link ch.alpenflight.migrations.domain.MigrationUpload}).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migrations.application;
