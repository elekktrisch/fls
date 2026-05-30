package ch.alpenflight.migrations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migrations.domain.BundleHeader;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import ch.alpenflight.migrations.domain.IngestFunnelTelemetry;
import ch.alpenflight.migrations.domain.MigrationBundleCipher;
import ch.alpenflight.migrations.domain.MigrationCryptoService;
import ch.alpenflight.migrations.domain.MigrationRun;
import ch.alpenflight.migrations.domain.MigrationRunRepository;
import ch.alpenflight.migrations.domain.MigrationRunState;
import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import ch.alpenflight.migrations.domain.MigrationUploadState;
import ch.alpenflight.migrations.domain.SecureBytes;
import ch.alpenflight.tenancy.provisioning.application.ClubSpec;
import ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningRequest;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.postgresql.copy.CopyManager;
import org.postgresql.jdbc.PgConnection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * S-141 streaming decrypt + ingest pipeline. Reads the encrypted ALPF
 * bundle off the servlet input stream, unwraps the per-upload session key,
 * provisions the Deployment + Clubs through S-138, and writes every
 * entity stream into the new-schema tables via S-183's {@link Mapper}
 * contract. Single Postgres transaction (AC4); per ADR 0022 directive 2
 * the FSM lives on the {@link MigrationRun} aggregate.
 *
 * <p>Concurrency: a single-permit semaphore bounds the number of in-flight
 * ingests to one — a 2 GB body holds substantial heap; the single-VPS
 * footprint cannot afford two parallel ingests. Second caller surfaces as
 * {@link BundleIngestErrorCode#DATABASE_CAPACITY_EXCEEDED} (429).
 *
 * <p>Plaintext-leak posture: every byte read off the encrypted body flows
 * through {@code StreamingAead → gzip → tar → JsonNode} in-memory; the
 * only persisted output is the Postgres rows. Disk sinks are
 * structurally banned inside this package via ArchUnit (see
 * {@code MigrationsIngestDiskSinkBanTest}).
 */
@Service
public class MigrationBundleIngestService {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationBundleIngestService.class);

    /** Cap on the encrypted-body byte count we accept (Vision §2 NFR). */
    public static final long MAX_BUNDLE_BYTES = 2L * 1024 * 1024 * 1024;

    /** Jackson parser tuned for an untrusted bundle (Security plan). */
    private static final ObjectMapper JSON = buildHardenedJsonMapper();

    private static final String MANIFEST_ENTRY_NAME = "manifest.json";
    private static final String NDJSON_ENTRY_SUFFIX = ".ndjson";
    private static final String LEGACY_ID_MAP_ENTRY_PREFIX = "legacy_id_map/";
    private static final String LEGACY_ID_MAP_ENTRY_SUFFIX = ".pgcopy";

    /**
     * Global ingest gate. Static so two service instances (test contexts
     * that re-init the bean) still share the cap.
     */
    private static final Semaphore INGEST_GATE = new Semaphore(1, true);

    private final MigrationUploadRepository uploads;
    private final MigrationRunRepository runs;
    private final MigrationCryptoService crypto;
    private final MigrationBundleCipher bundleCipher;
    private final DeploymentProvisioningService provisioning;
    private final IngestFunnelTelemetry telemetry;
    private final AuditTrail audit;
    private final EntityManager entityManager;
    private final Clock clock;

    public MigrationBundleIngestService(MigrationUploadRepository uploads,
                                        MigrationRunRepository runs,
                                        MigrationCryptoService crypto,
                                        MigrationBundleCipher bundleCipher,
                                        DeploymentProvisioningService provisioning,
                                        IngestFunnelTelemetry telemetry,
                                        AuditTrail audit,
                                        EntityManager entityManager,
                                        Clock clock) {
        this.uploads = uploads;
        this.runs = runs;
        this.crypto = crypto;
        this.bundleCipher = bundleCipher;
        this.provisioning = provisioning;
        this.telemetry = telemetry;
        this.audit = audit;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Top-level orchestration. Acquires the global ingest gate then drives
     * the transactional pipeline. On any failure, the run row is flipped
     * to {@code FAILED} + the upload row to {@code FAILED} in a separate
     * transaction so the failure trail survives even when the main txn
     * rolls back.
     *
     * @return the {@link IngestOutcome} with the new Deployment + Clubs.
     */
    public IngestOutcome ingest(UUID uploadId, UUID principalUserId, InputStream encryptedBody) {
        Objects.requireNonNull(uploadId, "uploadId");
        Objects.requireNonNull(principalUserId, "principalUserId");
        Objects.requireNonNull(encryptedBody, "encryptedBody");
        telemetry.uploadStarted(uploadId, clock.instant());

        if (!INGEST_GATE.tryAcquire()) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.DATABASE_CAPACITY_EXCEEDED,
                    "Another ingest is in flight; retry once the global semaphore frees");
        }
        try {
            telemetry.ingestStarted(uploadId, clock.instant());
            return ingestInTransaction(uploadId, principalUserId, encryptedBody);
        } finally {
            INGEST_GATE.release();
        }
    }

    @Transactional
    @SuppressWarnings("UnnecessaryAsync")
    public IngestOutcome ingestInTransaction(UUID uploadId,
                                             UUID principalUserId,
                                             InputStream encryptedBody) {
        Session session = entityManager.unwrap(Session.class);
        // AtomicReference is the lambda-escape idiom — UnnecessaryAsync's
        // "Did you mean a plain var" suggestion misses that the doWork lambda
        // body must read effectively-final state from the enclosing scope.
        AtomicReference<IngestOutcome> outcomeRef = new AtomicReference<>();
        AtomicReference<BundleIngestException> failureRef = new AtomicReference<>();
        AtomicReference<MigrationUpload> uploadRef = new AtomicReference<>();
        AtomicReference<MigrationRun> runRef = new AtomicReference<>();
        try {
            session.doWork(connection -> {
                applySingleTxnSettings(connection);
                MigrationUpload upload = lockUpload(uploadId);
                uploadRef.set(upload);
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
                runRef.set(run);

                audit.record(AuditAction.MIGRATION_INGEST_STARTED,
                        AuditedTarget.created(
                                MigrationIngestAuditSnapshot.AUDIT_ENTITY_TYPE,
                                uploadId,
                                MigrationIngestAuditSnapshot.started(uploadId)));

                byte[] wrappedPrivateKey = Objects.requireNonNull(upload.getPrivateKeyCiphertext(),
                        "AWAITING_UPLOAD row must carry a wrapped private key");
                BundleHeader header = readHeader(encryptedBody);
                IngestOutcome outcome = crypto.unwrapInto(uploadId, wrappedPrivateKey, rsaPrivateKey ->
                        drainDecryptedBody(connection, upload, run, header, encryptedBody, rsaPrivateKey));
                outcomeRef.set(outcome);
            });
        } catch (BundleIngestException e) {
            failureRef.set(e);
        } catch (RuntimeException unexpected) {
            LOG.error("MigrationBundleIngest: unexpected failure", unexpected);
            StringBuilder trace = new StringBuilder();
            trace.append(unexpected.getClass().getSimpleName()).append(": ")
                    .append(unexpected.getMessage());
            StackTraceElement[] frames = unexpected.getStackTrace();
            int framesToInclude = Math.min(frames.length, 6);
            for (int i = 0; i < framesToInclude; i++) {
                trace.append(" | ").append(frames[i].getClassName()).append('.')
                        .append(frames[i].getMethodName()).append(':')
                        .append(frames[i].getLineNumber());
            }
            failureRef.set(new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Unexpected failure inside ingest: " + trace,
                    unexpected));
        }
        if (failureRef.get() != null) {
            MigrationUpload upload = uploadRef.get();
            MigrationRun run = runRef.get();
            BundleIngestException failure = failureRef.get();
            if (upload != null && run != null) {
                applyFailure(upload, run, failure.getErrorCode(), shortDetail(failure));
            }
            throw failure;
        }
        IngestOutcome outcome = outcomeRef.get();
        if (outcome == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Ingest pipeline returned no outcome and no failure");
        }
        return outcome;
    }

    private IngestOutcome drainDecryptedBody(Connection connection,
                                             MigrationUpload upload,
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

            BundleManifest manifest = readManifestOrThrow(tarStream);
            new Manifest(manifest.schemaVersion(), manifest.entityPolicies(), manifest.unmappedReason());

            run.transitionTo(MigrationRunState.PROVISIONING);
            ProvisioningResult provisioned = provisionDeployment(upload, manifest);
            run.attachDeployment(provisioned.deploymentId());

            createTemporaryIdMapTables(connection);
            seedClubLegacyIdMap(connection, manifest, provisioned);

            run.transitionTo(MigrationRunState.INGESTING);
            drainEntityStreams(connection, run, tarStream, provisioned);

            run.transitionTo(MigrationRunState.COMPLETING);
            upload.markConsumed(clock);
            uploads.save(upload);
            run.markCompleted(clock);
            runs.save(run);

            UUID deploymentId = provisioned.deploymentId();
            audit.record(AuditAction.MIGRATION_INGEST_COMPLETED,
                    AuditedTarget.created(
                            MigrationIngestAuditSnapshot.AUDIT_ENTITY_TYPE,
                            upload.getRawId(),
                            MigrationIngestAuditSnapshot.completed(
                                    upload.getRawId(), deploymentId, provisioned.clubIds().size())));
            telemetry.ingestCompleted(upload.getRawId(), provisioned.clubIds().size(), clock.instant());

            return new IngestOutcome(deploymentId, provisioned.clubIds(), provisioned.primaryClubId());
        } catch (IOException tarFailure) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "Bundle tar / gzip read failed", tarFailure);
        } catch (SQLException sql) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.INGEST_INTERNAL_ERROR,
                    "Database error during ingest", sql);
        }
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

    private BundleHeader readHeader(InputStream encryptedBody) {
        byte[] fixedPrefix = readExactly(encryptedBody, BundleHeader.FIXED_PREFIX_BYTES);
        int wrappedKeyLen = BundleHeader.peekWrappedKeyLen(fixedPrefix);
        if (wrappedKeyLen > BundleHeader.MAX_WRAPPED_KEY_LEN || wrappedKeyLen <= 0) {
            throw new BundleHeader.MalformedHeaderException(
                    "wrappedKeyLen out of range: " + wrappedKeyLen);
        }
        byte[] wrappedKey = readExactly(encryptedBody, wrappedKeyLen);
        byte[] fullHeader = new byte[fixedPrefix.length + wrappedKey.length];
        System.arraycopy(fixedPrefix, 0, fullHeader, 0, fixedPrefix.length);
        System.arraycopy(wrappedKey, 0, fullHeader, fixedPrefix.length, wrappedKey.length);
        try {
            return BundleHeader.parse(fullHeader);
        } catch (BundleHeader.MalformedHeaderException malformed) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_HEADER_MALFORMED,
                    String.valueOf(malformed.getMessage()), malformed);
        }
    }

    private static byte[] readExactly(InputStream from, int count) {
        byte[] buffer = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read;
            try {
                read = from.read(buffer, offset, count - offset);
            } catch (IOException ioFailure) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_TRUNCATED,
                        "I/O error reading bundle header", ioFailure);
            }
            if (read == -1) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_TRUNCATED,
                        "EOF before " + count + " bytes were read from the bundle prefix");
            }
            offset += read;
        }
        return buffer;
    }

    private BundleManifest readManifestOrThrow(TarArchiveInputStream tar) throws IOException {
        TarArchiveEntry entry = tar.getNextEntry();
        if (entry == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_MISSING_ENTRIES,
                    "Bundle is empty — manifest.json must be entry 0");
        }
        if (!MANIFEST_ENTRY_NAME.equals(entry.getName())) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_MISSING_ENTRIES,
                    "First tar entry must be manifest.json, got " + entry.getName());
        }
        try {
            return JSON.readValue(tar, BundleManifest.class);
        } catch (IOException ioOrParse) {
            if (ioOrParse instanceof JsonProcessingException malformed) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.MANIFEST_INVALID,
                        "Manifest JSON failed to parse: " + malformed.getOriginalMessage(), malformed);
            }
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "I/O failure reading manifest entry", ioOrParse);
        } catch (IllegalArgumentException invalid) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.MANIFEST_INVALID,
                    "Manifest rejected: " + invalid.getMessage(), invalid);
        }
    }

    private ProvisioningResult provisionDeployment(MigrationUpload upload, BundleManifest manifest) {
        List<ClubSpec> specs = new ArrayList<>(manifest.clubs().size());
        for (BundleManifest.ClubDeclaration club : manifest.clubs()) {
            specs.add(new ClubSpec(club.name(), club.slug(), club.clubKey(),
                    club.publicRegistrationEnabled(), club.countryId(), club.clubStateId()));
        }
        ProvisioningRequest request = new ProvisioningRequest(
                upload.getRawId(),
                upload.getUserId(),
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

    private void createTemporaryIdMapTables(Connection connection) throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            for (EntityType entity : EntityType.values()) {
                statement.execute(
                        "CREATE TEMP TABLE " + LegacyIdMapTables.temporaryTableName(entity)
                                + " (legacy_guid uuid PRIMARY KEY, new_uuid uuid NOT NULL) "
                                + "ON COMMIT DROP");
            }
        }
    }

    private void seedClubLegacyIdMap(Connection connection,
                                     BundleManifest manifest,
                                     ProvisioningResult provisioned) throws SQLException {
        String table = LegacyIdMapTables.temporaryTableName(EntityType.CLUB);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + table + " (legacy_guid, new_uuid) VALUES (?, ?)")) {
            List<UUID> clubIds = provisioned.clubIds();
            for (int i = 0; i < manifest.clubs().size(); i++) {
                insert.setObject(1, manifest.clubs().get(i).legacyClubId());
                insert.setObject(2, clubIds.get(i));
                insert.addBatch();
            }
            insert.executeBatch();
        }
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("ANALYZE " + table);
        }
    }

    private void drainEntityStreams(Connection connection,
                                    MigrationRun run,
                                    TarArchiveInputStream tar,
                                    ProvisioningResult provisioned) throws IOException, SQLException {
        Map<EntityType, Mapper> mappers = new LinkedHashMap<>();
        for (Mapper mapper : KnownMappers.all()) {
            mappers.put(mapper.entityType(), mapper);
        }

        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            rejectUnsafeTarName(name);
            if (name.startsWith(LEGACY_ID_MAP_ENTRY_PREFIX) && name.endsWith(LEGACY_ID_MAP_ENTRY_SUFFIX)) {
                copyLegacyIdMap(connection, name, tar);
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
                Mapper mapper = mappers.get(entityType);
                if (mapper == null) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.MAPPER_NOT_AVAILABLE,
                            "No mapper registered for entity " + entityType);
                }
                run.noteCurrent(entityType.name(), provisioned.primaryClubId());
                runs.save(run);
                ingestEntityNdjson(connection, mapper, tar);
            } else {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_EXTRA_ENTRIES,
                        "Tar entry " + name + " does not match the bundle layout");
            }
        }
    }

    private static void rejectUnsafeTarName(String name) {
        if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TAR_PARSE_FAILED,
                    "Tar entry name rejected (filesystem-traversal defense): " + name);
        }
    }

    private static void copyLegacyIdMap(Connection connection,
                                        String tarEntryName,
                                        InputStream tarStream) throws SQLException {
        String entitySuffix = tarEntryName.substring(
                LEGACY_ID_MAP_ENTRY_PREFIX.length(),
                tarEntryName.length() - LEGACY_ID_MAP_ENTRY_SUFFIX.length());
        String table = "legacy_id_map_" + entitySuffix;
        try {
            PgConnection pg = connection.unwrap(PgConnection.class);
            CopyManager copy = pg.getCopyAPI();
            copy.copyIn("COPY " + table + " FROM STDIN BINARY",
                    new NonClosingInputStream(tarStream));
        } catch (IOException ioFailure) {
            throw new SQLException("I/O failure during COPY of " + table, ioFailure);
        }
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("ANALYZE " + table);
        }
    }

    private static void ingestEntityNdjson(Connection connection,
                                           Mapper mapper,
                                           InputStream tarStream) throws SQLException, IOException {
        String[] columns = mapper.columns();
        String insert = "INSERT INTO " + destinationTableFor(mapper.entityType()) + " ("
                + String.join(", ", columns) + ") VALUES ("
                + "?,".repeat(columns.length - 1) + "?)";
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            try (NonClosingBufferedReader lines = NonClosingBufferedReader.of(tarStream)) {
                String line;
                int batched = 0;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode row;
                    try {
                        row = JSON.readTree(line);
                    } catch (IOException parseFailure) {
                        throw new BundleIngestException(
                                BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                                "NDJSON parse failed on " + mapper.entityType(), parseFailure);
                    }
                    mapper.readEntity(row, ps);
                    ps.addBatch();
                    batched++;
                    if (batched >= 4096) {
                        ps.executeBatch();
                        batched = 0;
                    }
                }
                if (batched > 0) {
                    ps.executeBatch();
                }
            }
        }
    }

    private static String destinationTableFor(EntityType entityType) {
        return "t_" + entityType.temporaryTableSuffix();
    }

    private void applySingleTxnSettings(Connection connection) {
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.execute("SET LOCAL idle_in_transaction_session_timeout = 0");
            stmt.execute("SET LOCAL statement_timeout = 0");
            stmt.execute("SET LOCAL synchronous_commit = OFF");
            stmt.execute("SET LOCAL lock_timeout = '30s'");
        } catch (SQLException e) {
            LOG.warn("ingest: failed to apply single-txn settings — proceeding with defaults", e);
        }
    }

    /**
     * Runs in a fresh transaction so the failure trail survives even when
     * the caller's main txn rolls back. The upload row's
     * {@code markFailed} wipes the private-key ciphertext; the run row
     * carries the error code + redacted detail.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void applyFailure(MigrationUpload upload,
                             MigrationRun run,
                             BundleIngestErrorCode errorCode,
                             String detail) {
        try {
            MigrationUpload reloaded = uploads.findById(upload.getRawId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Upload " + upload.getRawId() + " disappeared during failure handling"));
            if (reloaded.getState() == MigrationUploadState.AWAITING_UPLOAD) {
                reloaded.markFailed(clock);
                uploads.save(reloaded);
            }
            MigrationRun reloadedRun = runs.findById(run.getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Run " + run.getId() + " disappeared during failure handling"));
            if (!reloadedRun.getState().isTerminal()) {
                reloadedRun.markFailed(errorCode.name(), detail, clock);
                runs.save(reloadedRun);
            }
            audit.record(AuditAction.MIGRATION_INGEST_FAILED,
                    AuditedTarget.created(
                            MigrationIngestAuditSnapshot.AUDIT_ENTITY_TYPE,
                            upload.getRawId(),
                            MigrationIngestAuditSnapshot.failed(
                                    upload.getRawId(), errorCode, "ingest")));
            telemetry.ingestFailed(upload.getRawId(), errorCode, clock.instant());
        } catch (RuntimeException secondaryFailure) {
            LOG.error("Failed to record ingest failure for upload {} (errorCode={}); main rollback proceeds",
                    upload.getRawId(), errorCode, secondaryFailure);
        }
    }

    private static String shortDetail(Throwable t) {
        if (t == null || t.getMessage() == null) {
            return "(no detail)";
        }
        String msg = t.getMessage();
        return msg.length() > 500 ? msg.substring(0, 500) + "…" : msg;
    }

    private static ObjectMapper buildHardenedJsonMapper() {
        ObjectMapper mapper = new ObjectMapper();
        StreamReadConstraints hardened = StreamReadConstraints.builder()
                .maxStringLength(1_000_000)
                .maxNumberLength(1000)
                .maxNestingDepth(50)
                .build();
        mapper.getFactory().setStreamReadConstraints(hardened);
        return mapper;
    }

    /** Outcome of a successful ingest — returned to the controller. */
    public record IngestOutcome(UUID deploymentId, List<UUID> clubIds, UUID primaryClubId) { }

    /**
     * Tar entries report their length up front but Apache Commons Compress
     * still calls {@link InputStream#close()} on the tar archive's internal
     * input when an iterator wrapper closes — we use {@code close} to mean
     * "stop reading this entry," not "kill the tar stream."
     */
    private static final class NonClosingInputStream extends InputStream {

        private final InputStream delegate;

        NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() {
            // intentional no-op
        }
    }

    /**
     * Reads UTF-8 lines off a tar entry without buffering across entries.
     * Apache Commons Compress's {@link TarArchiveInputStream} returns the
     * next entry only when the caller stops reading the current one;
     * wrapping with a regular {@code BufferedReader} would over-read.
     */
    private static final class NonClosingBufferedReader implements AutoCloseable {

        private final byte[] buffer = new byte[8192];
        private int bufferOffset;
        private int bufferLength;
        private final InputStream delegate;

        private NonClosingBufferedReader(InputStream delegate) {
            this.delegate = delegate;
        }

        static NonClosingBufferedReader of(InputStream delegate) {
            return new NonClosingBufferedReader(delegate);
        }

        @org.jspecify.annotations.Nullable String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                if (bufferOffset >= bufferLength) {
                    int read = delegate.read(buffer, 0, buffer.length);
                    if (read == -1) {
                        return line.length() == 0 ? null : line.toString();
                    }
                    bufferOffset = 0;
                    bufferLength = read;
                }
                while (bufferOffset < bufferLength) {
                    byte b = buffer[bufferOffset++];
                    if (b == '\n') {
                        return line.toString();
                    }
                    if (b == '\r') {
                        continue;
                    }
                    line.append((char) (b & 0xFF));
                }
            }
        }

        @Override
        public void close() {
            // tar stream owns its lifecycle
            Arrays.fill(buffer, (byte) 0);
        }
    }
}
