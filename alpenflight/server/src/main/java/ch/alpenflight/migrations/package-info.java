/**
 * Migrations module — owns the data-migration surface for legacy
 * (flsserver / flsweb) tenants transitioning to AlpenFlight. S-140 ships
 * the per-upload RSA keypair handshake substrate; S-141+ layer the
 * encrypted-bundle ingest pipeline on top.
 *
 * <p>Declared {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} so a future module that needs to reference the shared
 * {@code MigrationUploadState} enum (e.g. S-141 ingest stamping
 * {@code consumed}) may import the domain type directly. Cross-module
 * inserts into {@code t_migration_upload} stay disallowed by ADR 0023's
 * layering rules.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code migrations.domain} — {@link ch.alpenflight.migrations.domain.MigrationUpload}
 *       aggregate, {@link ch.alpenflight.migrations.domain.MigrationUploadState}
 *       enum, repository + crypto ports, domain exceptions.</li>
 *   <li>{@code migrations.application} — handshake service, hourly
 *       expiry job, funnel-telemetry adapter, audit snapshot record.</li>
 *   <li>{@code migrations.web} — REST controller + response DTO +
 *       exception handler.</li>
 *   <li>{@code migrations.infra} — Tink AEAD crypto bean + Spring Data
 *       JPA repository implementation.</li>
 * </ul>
 *
 * <p>Pre-tenant by design: rows in {@code t_migration_upload} pre-date
 * any Deployment / Club, so the entity carries no Hibernate
 * {@code @TenantId} and the hourly expiry job runs as an
 * {@link ch.alpenflight.platform.scheduling.UnscopedScheduledJob}.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.migrations;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
