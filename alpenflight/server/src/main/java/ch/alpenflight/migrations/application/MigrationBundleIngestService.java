package ch.alpenflight.migrations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
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
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S-141 streaming decrypt + ingest pipeline. Reads the encrypted ALPF
 * bundle off the servlet input stream, unwraps the per-upload session key,
 * provisions the Deployment + Clubs through S-138, and writes every
 * entity stream into the new-schema tables via S-183's {@link Mapper}
 * contract. Single Postgres transaction; per ADR 0022 directive 2 the FSM
 * lives on the {@link MigrationRun} aggregate.
 *
 * <p>S-141b factored the parsing + ingest details into
 * {@link BundleStreamReader} (header, manifest, tar safety) and
 * {@link EntityStreamIngestor} (NDJSON, COPY, INSERT builder, column
 * allow-list). This orchestrator keeps the transaction boundary, the
 * concurrency gate, principal/upload-row gating, provisioning, FSM
 * transitions, and the post-commit synchronization.
 *
 * <p>Concurrency: {@link IngestConcurrencyGate} bounds in-flight ingests
 * (default 1 — single-VPS heap cap). A second caller surfaces as
 * {@link BundleIngestErrorCode#DATABASE_CAPACITY_EXCEEDED} (429). The
 * per-upload race surfaces separately as {@code BUNDLE_INGEST_IN_PROGRESS}
 * (409) via the {@code PESSIMISTIC_WRITE} row lock.
 *
 * <p>Plaintext-leak posture: every byte read off the encrypted body flows
 * through {@code StreamingAead → gzip → tar → JsonNode} in-memory; the
 * only persisted output is the Postgres rows. Disk sinks are
 * structurally banned inside this package via ArchUnit (see
 * {@code MigrationIngestNoDiskSinkTest}).
 */
@Service
public class MigrationBundleIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationBundleIngestService.class);

    /** Cap on the encrypted-body byte count we accept (Vision §2 NFR). */
    public static final long MAX_BUNDLE_BYTES = 2L * 1024 * 1024 * 1024;

    private static final String NDJSON_ENTRY_SUFFIX = ".ndjson";
    private static final String LEGACY_ID_MAP_ENTRY_PREFIX = "legacy_id_map/";
    private static final String LEGACY_ID_MAP_ENTRY_SUFFIX = ".pgcopy";

    /**
     * Error codes that signal "client-side retry is safe with the same
     * upload row" — recording a failure on these would burn a still-valid
     * handshake. {@code DATABASE_CAPACITY_EXCEEDED} = the global gate
     * throttled this caller; {@code BUNDLE_TOO_LARGE} = the controller
     * pre-check rejected the body before the txn opened.
     */
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
                                        EntityStreamIngestor entityStreamIngestor) {
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
        // statement_timeout drains SQL ~1 minute (clamped to half of the wall
        // cap for sub-2-minute test budgets) BEFORE the orTimeout fires +
        // interrupts the worker — so a long SELECT aborts at the SQL layer
        // and the JDBC SQLException unwinds the txn cleanly, instead of the
        // interrupt racing the SQL driver.
        long timeoutMs = bundleTimeout.toMillis();
        this.sqlStatementTimeoutMs = Math.max(timeoutMs - 60_000L, timeoutMs / 2);
        this.bundleStreamReader = new BundleStreamReader();
        this.entityStreamIngestor = entityStreamIngestor;
    }

    /**
     * Top-level orchestration. Acquires the global ingest gate, opens the
     * ingest transaction via {@link TransactionTemplate}, and — only after
     * the transaction has fully rolled back — flips the {@code MigrationRun}
     * and {@code MigrationUpload} rows to {@code FAILED} via the
     * {@link MigrationFailureRecorder}'s {@code REQUIRES_NEW} writer.
     *
     * <p>Why not {@code @Transactional} on this method:
     * {@code @Transactional} would keep the outer connection's row locks
     * alive across the {@code recordFailure} call. {@code recordFailure}
     * runs in {@code REQUIRES_NEW} and would try to UPDATE the same
     * upload row the suspended outer txn already locked with
     * {@code PESSIMISTIC_WRITE} — a Postgres-invisible Spring deadlock
     * that hung CI for 20 min before the lock_timeout default kicked in
     * (it never did, because {@code SET LOCAL lock_timeout = '30s'} only
     * applied to the outer connection, not the {@code REQUIRES_NEW}
     * connection). Using {@code TransactionTemplate.execute} guarantees the
     * txn has committed-or-rolled-back before we hand to the recorder.
     *
     * @return the {@link IngestOutcome} with the new Deployment + Clubs.
     */
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
        // runIdRef escapes the worker so the post-rollback failure recorder
        // can stamp the run row when the ingest aborted after the
        // MigrationRun was already persisted.
        AtomicReference<UUID> runIdRef = new AtomicReference<>();
        // Wall-clock cap: the servlet thread blocks on the executor up to
        // bundleTimeout, then interrupts the worker. Defense-in-depth on
        // top of Postgres statement_timeout (the SQL layer kills long
        // queries first; the orTimeout + interrupt catches non-SQL hangs
        // — gzip / tar / NDJSON parsing on a hostile payload).
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
            tryRecordFailure(uploadId, runIdRef.get(), timeoutError);
            throw timeoutError;
        } catch (ExecutionException wrapper) {
            Throwable cause = wrapper.getCause() == null ? wrapper : wrapper.getCause();
            if (cause instanceof BundleIngestException be) {
                // txTemplate.execute already rolled back; locks released. Safe
                // to call recordFailure (REQUIRES_NEW) without deadlocking on
                // the upload row — but only when the failure mode is one the
                // upload row should reflect. Pre-txn rejections that signal
                // "client retry with the same handshake is safe" stay clear of
                // the recorder so they don't burn the upload.
                if (!RETRYABLE_PRE_TRANSACTION_CODES.contains(be.getErrorCode())) {
                    tryRecordFailure(uploadId, runIdRef.get(), be);
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
            tryRecordFailure(uploadId, runIdRef.get(), wrapped);
            throw wrapped;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            BundleIngestException wrapped = new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Ingest interrupted while awaiting worker completion",
                    interrupted);
            tryRecordFailure(uploadId, runIdRef.get(), wrapped);
            throw wrapped;
        } finally {
            concurrencyGate.release();
        }
    }

    /**
     * Calls the REQUIRES_NEW failure recorder but never lets a secondary
     * failure (deadlock, lost connection mid-commit) shadow the original
     * ingest exception — the caller's surface stays the bundle-ingest
     * outcome the client experienced, while the recorder's own failure
     * lands as a server log line.
     */
    private void tryRecordFailure(UUID uploadId,
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

    /**
     * Opens the single-txn ingest pipeline inside a {@link TransactionTemplate}.
     * Returns the outcome on success; throws on failure — the caller
     * ({@link #ingest}) translates the failure into a {@code recordFailure}
     * call after the txn has rolled back.
     */
    private IngestOutcome runInsideTransaction(UUID uploadId,
                                               UUID principalUserId,
                                               UUID principalKeycloakSub,
                                               InputStream encryptedBody,
                                               AtomicReference<UUID> runIdRef) {
        telemetry.ingestStarted(uploadId, clock.instant());
        IngestOutcome result = txTemplate.execute(status -> {
            // Pre-decrypt Deployment-existence guard: per Security plan, refuse
            // 409 BEFORE allocating RSA + StreamingAead. ux_deployment_owner_active
            // is the structural source of truth; this is just the fail-fast
            // signal so a returning caller doesn't pay the crypto cost.
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
            // Post-commit audit + funnel emission: TransactionTemplate.execute
            // commits this lambda's outcome on normal return. The after-commit
            // hook fires AFTER the rows + Deployment land, so an audit-write
            // failure does NOT roll the ingest back; log + swallow so the
            // caller sees a clean 200 (the data is committed; reconciling a
            // missing audit / funnel event is an ops job, not the user's).
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
        // TransactionTemplate.execute may return null only if the callback
        // returns null, which it cannot here — the throw-path is the only
        // null-out. Surface the impossible-null defensively.
        if (result == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Ingest pipeline returned no outcome and no failure");
        }
        return result;
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
            // Constructor invokes the entity-policy allow-list validator on
            // the bundle-module side (S-183 Manifest). Side-effect only —
            // throws IllegalArgumentException on a violating tenantBypassFks
            // entry.
            new Manifest(manifest.schemaVersion(), manifest.entityPolicies(), manifest.unmappedReason());

            run.transitionTo(MigrationRunState.PROVISIONING);
            ProvisioningResult provisioned = provisionDeployment(upload, principalKeycloakSub, manifest);
            run.attachDeployment(provisioned.deploymentId());

            entityStreamIngestor.createTemporaryIdMapTables(connection);
            entityStreamIngestor.seedClubLegacyIdMap(connection, manifest, provisioned);

            run.transitionTo(MigrationRunState.INGESTING);
            try (ForeignKeyResolver foreignKeyResolver = new ForeignKeyResolver(connection, manifest);
                    ReferenceLookupResolver referenceLookupResolver =
                            new ReferenceLookupResolver(connection)) {
                drainEntityStreams(connection, run, tarStream, manifest, provisioned,
                        foreignKeyResolver, referenceLookupResolver);
            }

            run.transitionTo(MigrationRunState.COMPLETING);
            upload.markConsumed(clock);
            uploads.save(upload);
            run.markCompleted(clock);
            runs.save(run);

            return new IngestOutcome(provisioned.deploymentId(), provisioned.clubIds(), provisioned.primaryClubId());
        } catch (BundleCipherException cipherFailure) {
            // The cipher lives in the shared bundle library and speaks its own
            // crypto-local vocabulary (BundleCipherException.Failure). Map it
            // back onto the ingest error code the exception handler + the
            // negative-path ITs expect, preserving the original cause + message.
            // Only the two cipher resource-acquisitions above (unwrapSessionKey /
            // newDecryptingStream) can raise this; every other failure surfaces
            // as its own BundleIngestException untouched.
            throw toIngestException(cipherFailure);
        } catch (IOException tarFailure) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "Bundle tar / gzip read failed", tarFailure);
        } catch (SQLException sql) {
            // SQLState is a bounded enum-shaped identifier — safe for the
            // response body. The raw sql.getMessage() can echo failing
            // column values verbatim (e.g. attacker-influenced manifest
            // deploymentName on a constraint violation), so it stays in
            // the server-side log only — the BundleIngestException's
            // cause carries it through the exception handler's LOG.warn.
            String state = sql.getSQLState() == null ? "?" : sql.getSQLState();
            throw new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Database error during ingest [sqlstate=" + state + "]",
                    sql);
        }
    }

    /**
     * Map a crypto-local {@link BundleCipherException} onto the server-side
     * {@link BundleIngestException} so the RFC 7807 surface is unchanged.
     * Preserves the original message + cause.
     */
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
            // First statement in the loop body — name-safety check runs
            // BEFORE any prefix dispatch so a hostile entry name like
            // legacy_id_map/../etc/passwd.pgcopy cannot reach the COPY path.
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

    /**
     * Per-entity Club hint stamped on the run row for SPA progress polling.
     * Save frequency stays per-entity (28 writes/bundle) — per-row would
     * thrash the run table for invisible-to-SPA fidelity (poll cadence ~1s).
     * {@link EntityPolicy.PortPolicy#SYSTEM_GLOBAL_RESOLVE} entries write
     * into reference tables shared across all Clubs, so the Club hint is
     * {@code null} for those; tenant-scoped entities show {@code primaryClubId}.
     */
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
        // statement_timeout / lock_timeout protect other tenants from a
        // runaway ingest at the SQL layer; failing to apply them silently
        // would leave Postgres on defaults (typically unbounded), so
        // surface the SQLException to the orchestrator's wrapping catch
        // — INGEST_INTERNAL_ERROR aborts the txn before any heavy work.
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("SET LOCAL idle_in_transaction_session_timeout = 0");
            // SQL-layer cap fires ~1 minute before the orTimeout wall-clock
            // (see sqlStatementTimeoutMs computation) — a runaway query
            // aborts at the JDBC layer and the txn unwinds cleanly, ahead
            // of the worker-interrupt path.
            stmt.execute("SET LOCAL statement_timeout = " + sqlStatementTimeoutMs);
            stmt.execute("SET LOCAL synchronous_commit = OFF");
            stmt.execute("SET LOCAL lock_timeout = '30s'");
        }
    }

    private static String shortDetail(Throwable t) {
        if (t == null || t.getMessage() == null) {
            return "(no detail)";
        }
        String msg = t.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }

    /** Outcome of a successful ingest — returned to the controller. */
    public record IngestOutcome(UUID deploymentId, List<UUID> clubIds, UUID primaryClubId) { }
}
