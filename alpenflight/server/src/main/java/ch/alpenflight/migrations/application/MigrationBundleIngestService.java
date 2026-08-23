package ch.alpenflight.migrations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.flights.application.FlightReportRebuildService;
import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.crypto.BundleCipherException;
import ch.alpenflight.migration.bundle.crypto.BundleHeader;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import ch.alpenflight.migrations.domain.IngestFunnelTelemetry;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migrations.domain.MigrationCryptoService;
import ch.alpenflight.migrations.domain.MigrationRun;
import ch.alpenflight.migrations.domain.MigrationRunRepository;
import ch.alpenflight.migrations.domain.MigrationRunState;
import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import ch.alpenflight.migrations.domain.MigrationUploadState;
import ch.alpenflight.migration.bundle.crypto.SecureBytes;
import ch.alpenflight.tenancy.provisioning.application.ClubSpec;
import ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningRequest;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningResult;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationBundleIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationBundleIngestService.class);

    public static final long MAX_BUNDLE_BYTES = 2L * 1024 * 1024 * 1024;

    private static final String NDJSON_ENTRY_SUFFIX = ".ndjson";
    private static final String LEGACY_ID_MAP_ENTRY_PREFIX = "legacy_id_map/";
    private static final String LEGACY_ID_MAP_ENTRY_SUFFIX = ".pgcopy";

    private static final long SQL_TIMEOUT_HEADROOM_BEFORE_WALL_CLOCK_MS = 60_000L;

    private static final Set<BundleIngestErrorCode> RETRYABLE_PRE_TRANSACTION_CODES =
            EnumSet.of(
                    BundleIngestErrorCode.DATABASE_CAPACITY_EXCEEDED,
                    BundleIngestErrorCode.BUNDLE_TOO_LARGE);

    private final MigrationUploadRepository uploads;
    private final MigrationRunRepository runs;
    private final MigrationCryptoService crypto;
    private final MigrationBundleCipher bundleCipher;
    private final DeploymentProvisioningService provisioning;
    private final DeploymentRepository deployments;
    private final IngestFunnelTelemetry telemetry;
    private final AuditTrail audit;
    private final MigrationFailureRecorder failureRecorder;
    private final EntityManager entityManager;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final IngestConcurrencyGate concurrencyGate;
    private final AsyncTaskExecutor ingestExecutor;
    private final Duration bundleTimeout;
    private final long sqlStatementTimeoutMs;
    private final BundleStreamReader bundleStreamReader;
    private final EntityStreamIngestor entityStreamIngestor;
    private final FlightReportRebuildService flightReportRebuild;
    private final boolean unmaskConstraintNamesInDevAndTest;

    public MigrationBundleIngestService(MigrationUploadRepository uploads,
                                        MigrationRunRepository runs,
                                        MigrationCryptoService crypto,
                                        MigrationBundleCipher bundleCipher,
                                        DeploymentProvisioningService provisioning,
                                        DeploymentRepository deployments,
                                        IngestFunnelTelemetry telemetry,
                                        AuditTrail audit,
                                        MigrationFailureRecorder failureRecorder,
                                        EntityManager entityManager,
                                        PlatformTransactionManager txManager,
                                        Clock clock,
                                        IngestConcurrencyGate concurrencyGate,
                                        @Qualifier("applicationTaskExecutor")
                                        AsyncTaskExecutor ingestExecutor,
                                        @Value("${alpenflight.migration.bundle-timeout:PT15M}")
                                        Duration bundleTimeout,
                                        EntityStreamIngestor entityStreamIngestor,
                                        FlightReportRebuildService flightReportRebuild,
                                        Environment environment) {
        this.uploads = uploads;
        this.runs = runs;
        this.crypto = crypto;
        this.bundleCipher = bundleCipher;
        this.provisioning = provisioning;
        this.deployments = deployments;
        this.telemetry = telemetry;
        this.audit = audit;
        this.failureRecorder = failureRecorder;
        this.entityManager = entityManager;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
        this.concurrencyGate = concurrencyGate;
        this.ingestExecutor = ingestExecutor;
        if (bundleTimeout == null || bundleTimeout.isZero() || bundleTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "alpenflight.migration.bundle-timeout must be a positive Duration, got " + bundleTimeout);
        }
        this.bundleTimeout = bundleTimeout;
        long wallClockCapMs = bundleTimeout.toMillis();
        this.sqlStatementTimeoutMs = Math.max(
                wallClockCapMs - SQL_TIMEOUT_HEADROOM_BEFORE_WALL_CLOCK_MS, wallClockCapMs / 2);
        this.bundleStreamReader = new BundleStreamReader();
        this.entityStreamIngestor = entityStreamIngestor;
        this.flightReportRebuild = flightReportRebuild;
        this.unmaskConstraintNamesInDevAndTest =
                environment.acceptsProfiles(org.springframework.core.env.Profiles.of("dev", "test"));
    }

    @SuppressWarnings("UnnecessaryAsync")
    public IngestOutcome ingest(UUID uploadId,
                                UUID principalUserId,
                                UUID principalKeycloakSub,
                                InputStream encryptedBody) {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(principalUserId, "principalUserId");
        Objects.requireNonNull(principalKeycloakSub, "principalKeycloakSub");
        Objects.requireNonNull(encryptedBody, "encryptedBody");
        telemetry.uploadStarted(uploadId, clock.instant());

        if (!concurrencyGate.tryAcquire()) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.DATABASE_CAPACITY_EXCEEDED,
                    "Another ingest is in flight; retry once the global gate frees");
        }
        AtomicReference<UUID> runIdRef = new AtomicReference<>();
        Future<IngestOutcome> future = ingestExecutor.submit(() ->
                runInsideTransaction(uploadId, principalUserId, principalKeycloakSub,
                        encryptedBody, runIdRef));
        try {
            return future.get(bundleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            BundleIngestException timeoutError = new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TIMEOUT,
                    "Bundle ingest exceeded " + bundleTimeout + " wall-clock cap; worker interrupted",
                    timeout);
            recordFailureAfterRollbackReleasedLocks(uploadId, runIdRef.get(), timeoutError);
            throw timeoutError;
        } catch (ExecutionException wrapper) {
            Throwable cause = wrapper.getCause() == null ? wrapper : wrapper.getCause();
            if (cause instanceof BundleIngestException be) {
                if (!RETRYABLE_PRE_TRANSACTION_CODES.contains(be.getErrorCode())) {
                    recordFailureAfterRollbackReleasedLocks(uploadId, runIdRef.get(), be);
                }
                throw be;
            }
            LOG.error("MigrationBundleIngest: unexpected failure for upload {}",
                    uploadId, cause);
            BundleIngestException wrapped = new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Unexpected ingest failure: " + cause.getClass().getSimpleName()
                            + ": " + cause.getMessage(),
                    cause);
            recordFailureAfterRollbackReleasedLocks(uploadId, runIdRef.get(), wrapped);
            throw wrapped;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            BundleIngestException wrapped = new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Ingest interrupted while awaiting worker completion",
                    interrupted);
            recordFailureAfterRollbackReleasedLocks(uploadId, runIdRef.get(), wrapped);
            throw wrapped;
        } finally {
            concurrencyGate.release();
        }
    }

    private void recordFailureAfterRollbackReleasedLocks(UUID uploadId,
                                                        @Nullable UUID runId,
                                                        BundleIngestException original) {
        try {
            failureRecorder.recordFailure(uploadId, runId,
                    original.getErrorCode(), shortDetail(original));
        } catch (RuntimeException secondary) {
            LOG.error("MigrationBundleIngest: failureRecorder threw while recording {} for "
                            + "upload {} — original exception preserved for the caller",
                    original.getErrorCode(), uploadId, secondary);
        }
    }

    private IngestOutcome runInsideTransaction(UUID uploadId,
                                               UUID principalUserId,
                                               UUID principalKeycloakSub,
                                               InputStream encryptedBody,
                                               AtomicReference<UUID> runIdRef) {
        telemetry.ingestStarted(uploadId, clock.instant());
        IngestOutcome result = txTemplate.execute(status -> {
            Optional<Deployment> activeOwner = deployments.findActiveByOwner(principalKeycloakSub);
            if (activeOwner.isPresent()) {
                Deployment existing = activeOwner.get();
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("existingDeploymentId",
                        existing.getId() == null ? "" : existing.getId().toString());
                throw new BundleIngestException(
                        BundleIngestErrorCode.DEPLOYMENT_EXISTS,
                        "Caller already owns a non-terminal Deployment",
                        attrs,
                        null);
            }

            Session session = entityManager.unwrap(Session.class);
            AtomicReference<IngestOutcome> outcomeRef = new AtomicReference<>();
            session.doWork(connection -> {
                applySingleTxnSettings(connection);
                MigrationUpload upload = lockUpload(uploadId);
                if (!upload.getUserId().equals(principalUserId)) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.BUNDLE_FORBIDDEN,
                            "Upload " + uploadId + " not owned by caller");
                }
                if (upload.getState() != MigrationUploadState.AWAITING_UPLOAD) {
                    throw nonAwaitingUploadException(upload);
                }

                MigrationRun run = MigrationRun.start(UuidCreator.getTimeOrderedEpoch(), uploadId, clock);
                runs.save(run);
                runIdRef.set(run.getId());

                audit.record(AuditAction.MIGRATION_INGEST_STARTED,
                        AuditedTarget.created(
                                MigrationIngestAuditSnapshot.AUDIT_ENTITY_TYPE,
                                uploadId,
                                MigrationIngestAuditSnapshot.started(uploadId)));

                byte[] wrappedPrivateKey = Objects.requireNonNull(upload.getPrivateKeyCiphertext(),
                        "AWAITING_UPLOAD row must carry a wrapped private key");
                BundleHeader header = bundleStreamReader.readHeader(encryptedBody);
                IngestOutcome outcome = crypto.unwrapInto(uploadId, wrappedPrivateKey, rsaPrivateKey ->
                        drainDecryptedBody(connection, upload, principalKeycloakSub, run, header,
                                encryptedBody, rsaPrivateKey));
                outcomeRef.set(outcome);
            });

            IngestOutcome outcome = outcomeRef.get();
            if (outcome == null) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                        "Ingest pipeline returned no outcome and no failure");
            }
            UUID resolvedDeploymentId = outcome.deploymentId();
            int resolvedClubCount = outcome.clubIds().size();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        audit.record(AuditAction.MIGRATION_INGEST_COMPLETED,
                                AuditedTarget.created(
                                        MigrationIngestAuditSnapshot.AUDIT_ENTITY_TYPE,
                                        uploadId,
                                        MigrationIngestAuditSnapshot.completed(
                                                uploadId, resolvedDeploymentId, resolvedClubCount)));
                        telemetry.ingestCompleted(uploadId, resolvedClubCount, clock.instant());
                    } catch (RuntimeException postCommitFailure) {
                        LOG.error("MigrationBundleIngest: post-commit audit / telemetry "
                                        + "failed for upload {} (Deployment {} is committed and "
                                        + "the caller will see 200)",
                                uploadId, resolvedDeploymentId, postCommitFailure);
                    }
                }
            });
            return outcome;
        });
        if (result == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Ingest pipeline returned no outcome and no failure");
        }
        rebuildFlightReportReadModelAfterCommit(result);
        return result;
    }

    private void rebuildFlightReportReadModelAfterCommit(IngestOutcome outcome) {
        for (UUID clubId : outcome.clubIds()) {
            try {
                flightReportRebuild.rebuildForClub(clubId);
            } catch (RuntimeException rebuildFailure) {
                LOG.error("MigrationBundleIngest: flight-report read-model rebuild failed "
                                + "for club {} (Deployment {} is committed; rebuild is "
                                + "idempotent — re-run it for this club)",
                        clubId, outcome.deploymentId(), rebuildFailure);
            }
        }
    }

    private IngestOutcome drainDecryptedBody(Connection connection,
                                             MigrationUpload upload,
                                             UUID principalKeycloakSub,
                                             MigrationRun run,
                                             BundleHeader header,
                                             InputStream encryptedBody,
                                             SecureBytes rsaPrivateKey) {
        try (SecureBytes sessionKey = bundleCipher.unwrapSessionKey(
                rsaPrivateKey.bytes(), header.wrappedSessionKey());
             InputStream decryptingStream = bundleCipher.newDecryptingStream(
                     sessionKey, upload.getRawId(), encryptedBody);
             GzipCompressorInputStream gzipStream = new GzipCompressorInputStream(decryptingStream);
             TarArchiveInputStream tarStream = new TarArchiveInputStream(gzipStream)) {

            BundleManifest manifest = bundleStreamReader.readManifestOrThrow(tarStream);
            if (manifest.schemaVersion() != Manifest.CURRENT_SCHEMA_VERSION) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.SCHEMA_VERSION_MISMATCH,
                        "Bundle schemaVersion=" + manifest.schemaVersion()
                                + " does not match server schemaVersion=" + Manifest.CURRENT_SCHEMA_VERSION);
            }
            if (manifest.clubs().isEmpty()) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.MANIFEST_EMPTY_CLUBS,
                        "Bundle manifest must declare at least one Club; provisioning requires 1..N");
            }
            rejectEntityPoliciesOutsideAllowList(manifest);

            run.transitionTo(MigrationRunState.PROVISIONING);
            ProvisioningResult provisioned = provisionDeployment(upload, principalKeycloakSub, manifest);
            run.attachDeployment(provisioned.deploymentId());

            provisioning.provisionMigratedClubAdmins(provisioned.clubIds());

            entityStreamIngestor.createTemporaryIdMapTables(connection);
            entityStreamIngestor.seedClubLegacyIdMap(connection, manifest, provisioned);

            run.transitionTo(MigrationRunState.INGESTING);
            try (ForeignKeyResolver foreignKeyResolver = new ForeignKeyResolver(connection, manifest);
                    ReferenceLookupResolver referenceLookupResolver =
                            new ReferenceLookupResolver(connection)) {
                drainEntityStreams(connection, run, tarStream, manifest, provisioned,
                        foreignKeyResolver, referenceLookupResolver);
            }
            reportTenantBackfill(
                    MigratedAuditRowTenantBackfill
                            .giveEachMigratedRowTheTenantOfTheEntityItDescribes(connection));

            run.transitionTo(MigrationRunState.COMPLETING);
            upload.markConsumed(clock);
            uploads.save(upload);
            run.markCompleted(clock);
            runs.save(run);

            return new IngestOutcome(provisioned.deploymentId(), provisioned.clubIds(), provisioned.primaryClubId());
        } catch (BundleCipherException cipherFailure) {
            throw toIngestException(cipherFailure);
        } catch (IOException tarFailure) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "Bundle tar / gzip read failed", tarFailure);
        } catch (SQLException sql) {
            String bodySafeSqlState = sql.getSQLState() == null ? "?" : sql.getSQLState();
            String constraint =
                    unmaskConstraintNamesInDevAndTest ? violatedConstraintName(sql) : null;
            String detail = constraint == null
                    ? "Database error during ingest [sqlstate=" + bodySafeSqlState + "]"
                    : "Database error during ingest [sqlstate=" + bodySafeSqlState
                            + ", constraint=" + constraint + "]";
            throw new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    detail,
                    sql);
        }
    }

    private static void reportTenantBackfill(MigratedAuditRowTenantBackfill.Result result) {
        LOG.info("MigrationBundleIngest: {} migrated audit rows now carry the club of the entity "
                        + "they describe; {} stay without a club because the entity they describe "
                        + "is cross-tenant, fanned out or not migrated",
                result.rowsGivenTheTenantOfTheEntityTheyDescribe(),
                result.rowsWhoseDescribedEntityYieldsNoClub());
    }

    private static void rejectEntityPoliciesOutsideAllowList(BundleManifest manifest) {
        new Manifest(manifest.schemaVersion(), manifest.entityPolicies(), manifest.unmappedReason());
    }

    private static BundleIngestException toIngestException(BundleCipherException cipherFailure) {
        BundleIngestErrorCode code = switch (cipherFailure.failure()) {
            case RSA_UNWRAP_FAILED -> BundleIngestErrorCode.BUNDLE_DECRYPT_RSA_UNWRAP_FAILED;
            case AEAD_TAG_FAILED -> BundleIngestErrorCode.BUNDLE_DECRYPT_AEAD_TAG_FAILED;
            case INTERNAL -> BundleIngestErrorCode.INGEST_INTERNAL_ERROR;
        };
        String detail = Objects.requireNonNullElse(cipherFailure.getMessage(), "Bundle cipher failure");
        return new BundleIngestException(code, detail, cipherFailure);
    }

    private MigrationUpload lockUpload(UUID uploadId) {
        try {
            return entityManager.createQuery(
                            "select u from MigrationUpload u where u.id = :id",
                            MigrationUpload.class)
                    .setParameter("id", uploadId)
                    .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                    .setHint("jakarta.persistence.lock.timeout", 0)
                    .getSingleResult();
        } catch (jakarta.persistence.NoResultException none) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_FORBIDDEN,
                    "Unknown uploadId " + uploadId);
        } catch (PessimisticLockException | LockTimeoutException locked) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_INGEST_IN_PROGRESS,
                    "Another ingest holds the lock on upload " + uploadId, locked);
        } catch (PessimisticLockingFailureException locked) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_INGEST_IN_PROGRESS,
                    "Another ingest holds the lock on upload " + uploadId, locked);
        }
    }

    private BundleIngestException nonAwaitingUploadException(MigrationUpload upload) {
        BundleIngestErrorCode code = switch (upload.getState()) {
            case AWAITING_UPLOAD -> BundleIngestErrorCode.INGEST_INTERNAL_ERROR;
            case CONSUMED -> BundleIngestErrorCode.BUNDLE_ALREADY_CONSUMED;
            case SUPERSEDED, EXPIRED -> BundleIngestErrorCode.BUNDLE_HANDSHAKE_EXPIRED;
            case FAILED -> BundleIngestErrorCode.BUNDLE_PRIOR_RUN_FAILED;
        };
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("uploadState", upload.getState().name());
        return new BundleIngestException(code, "Upload " + upload.getRawId()
                + " is in state " + upload.getState(), attrs, null);
    }

    private ProvisioningResult provisionDeployment(MigrationUpload upload,
                                                   UUID principalKeycloakSub,
                                                   BundleManifest manifest) {
        List<ClubSpec> specs = new ArrayList<>(manifest.clubs().size());
        for (BundleManifest.ClubDeclaration club : manifest.clubs()) {
            specs.add(new ClubSpec(club.name(), club.slug(), club.clubKey(),
                    club.publicRegistrationEnabled(), club.countryId(), club.clubStateId()));
        }
        ProvisioningRequest request = new ProvisioningRequest(
                upload.getRawId(),
                principalKeycloakSub,
                manifest.deploymentName(),
                specs,
                manifest.primaryClubId());
        try {
            return provisioning.provision(request);
        } catch (ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException exists) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("existingDeploymentId", exists.existingDeploymentId().toString());
            throw new BundleIngestException(
                    BundleIngestErrorCode.DEPLOYMENT_EXISTS,
                    "Caller already owns a non-terminal Deployment", attrs, exists);
        }
    }

    private void drainEntityStreams(Connection connection,
                                    MigrationRun run,
                                    TarArchiveInputStream tar,
                                    BundleManifest manifest,
                                    ProvisioningResult provisioned,
                                    ForeignKeyResolver foreignKeyResolver,
                                    ReferenceLookupResolver referenceLookupResolver)
            throws IOException, SQLException {
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            BundleStreamReader.rejectUnsafeTarName(name);
            if (name.startsWith(LEGACY_ID_MAP_ENTRY_PREFIX)
                    && name.endsWith(LEGACY_ID_MAP_ENTRY_SUFFIX)) {
                entityStreamIngestor.copyLegacyIdMap(connection, name, tar);
            } else if (name.endsWith(NDJSON_ENTRY_SUFFIX)) {
                String entityName = name.substring(0, name.length() - NDJSON_ENTRY_SUFFIX.length());
                EntityType entityType;
                try {
                    entityType = EntityType.valueOf(entityName);
                } catch (IllegalArgumentException unknown) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.BUNDLE_EXTRA_ENTRIES,
                            "Tar entry " + name + " does not map to a known EntityType", unknown);
                }
                Mapper mapper = entityStreamIngestor.mapperFor(entityType);
                run.noteCurrent(entityType.name(),
                        currentClubFor(entityType, manifest, provisioned));
                runs.save(run);
                entityStreamIngestor.ingestEntityNdjson(connection, mapper, tar,
                        foreignKeyResolver, referenceLookupResolver);
            } else {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_EXTRA_ENTRIES,
                        "Tar entry " + name + " does not match the bundle layout");
            }
        }
    }

    private static @Nullable UUID currentClubFor(EntityType entityType,
                                                 BundleManifest manifest,
                                                 ProvisioningResult provisioned) {
        EntityPolicy policy = manifest.entityPolicies().get(entityType);
        if (policy != null && policy.portPolicy() == EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE) {
            return null;
        }
        return provisioned.primaryClubId();
    }

    private void applySingleTxnSettings(Connection connection) throws SQLException {
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("SET LOCAL idle_in_transaction_session_timeout = 0");
            stmt.execute("SET LOCAL statement_timeout = " + sqlStatementTimeoutMs);
            stmt.execute("SET LOCAL synchronous_commit = OFF");
            stmt.execute("SET LOCAL lock_timeout = '30s'");
        }
    }

    private static @Nullable String violatedConstraintName(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof org.postgresql.util.PSQLException psql) {
                org.postgresql.util.ServerErrorMessage serverError = psql.getServerErrorMessage();
                if (serverError != null && serverError.getConstraint() != null) {
                    return serverError.getConstraint();
                }
            }
        }
        return null;
    }

    private static String shortDetail(Throwable t) {
        if (t == null || t.getMessage() == null) {
            return "(no detail)";
        }
        String msg = t.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }

    public record IngestOutcome(UUID deploymentId, List<UUID> clubIds, UUID primaryClubId) { }
}
