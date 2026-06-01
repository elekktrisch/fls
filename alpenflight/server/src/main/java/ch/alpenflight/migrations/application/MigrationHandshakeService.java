package ch.alpenflight.migrations.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.migrations.domain.HandshakeFunnelTelemetry;
import ch.alpenflight.migrations.domain.MigrationCryptoService;
import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import ch.alpenflight.migration.bundle.crypto.PemEncoders;
import com.github.f4b6a3.uuid.UuidCreator;
import jakarta.persistence.EntityManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case service for the /handshake endpoints. Owns:
 *
 * <ul>
 *   <li>Per-upload RSA-4096 keypair generation.</li>
 *   <li>Wrapping the private key under the master Tink keyset.</li>
 *   <li>Inserting the row in {@code awaiting_upload}, racing against the
 *       partial UNIQUE — the loser of a two-tab race catches the
 *       constraint violation and replays (supersede prior + insert new)
 *       in the same transaction.</li>
 *   <li>Audit-trail emission (ISSUED + SUPERSEDED) and funnel-telemetry
 *       signals.</li>
 * </ul>
 *
 * <p>{@link #findCurrent} is the SPA mount-restore path — pure read of the
 * caller's current in-flight row (sans private key, surfaced as the
 * application-layer {@link MigrationUploadView}).
 */
@Service
public class MigrationHandshakeService {

    /** Vision C28: the public key is valid for 24 hours after issuance. */
    public static final Duration HANDSHAKE_TTL = Duration.ofHours(24);

    private static final int RSA_KEY_BITS = 4096;

    private final MigrationUploadRepository repository;
    private final MigrationCryptoService crypto;
    private final HandshakeFunnelTelemetry telemetry;
    private final AuditTrail audit;
    private final PreTenantUserLookup userLookup;
    // EntityManager dep is the only concession this layer makes to JPA: the
    // race-loser recovery path needs to detach the locally-built row before
    // the txn commit re-tries the INSERT. Adding a repository port for
    // detach() is awkward because Spring Data JPA's fragment auto-detection
    // doesn't pick up package-private fragments cleanly across module
    // boundaries — kept inline rather than building infra around one call.
    private final EntityManager entityManager;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public MigrationHandshakeService(MigrationUploadRepository repository,
                                     MigrationCryptoService crypto,
                                     HandshakeFunnelTelemetry telemetry,
                                     AuditTrail audit,
                                     PreTenantUserLookup userLookup,
                                     EntityManager entityManager,
                                     Clock clock) {
        this.repository = repository;
        this.crypto = crypto;
        this.telemetry = telemetry;
        this.audit = audit;
        this.userLookup = userLookup;
        this.entityManager = entityManager;
        this.clock = clock;
        // Pre-warm SecureRandom at construction time so the first
        // handshake doesn't pay the entropy-source initialisation cost
        // (~100ms on a cold JVM).
        this.secureRandom = strongRandom();
    }

    @Transactional
    public MigrationUploadView issue(Jwt jwt) {
        UUID userId = userLookup.resolveUserId(jwt)
                .orElseThrow(() -> new UnknownPrincipalException(
                        "No t_user row for principal — verified-email signup expected"));

        Optional<MigrationUpload> prior = repository.findAwaitingByUser(userId);
        MigrationUpload upload = insertFreshOrSupersede(userId, prior);
        UUID uploadId = upload.getRawId();

        audit.record(AuditAction.MIGRATION_HANDSHAKE_ISSUED,
                AuditedTarget.created(MigrationUploadAuditSnapshot.AUDIT_ENTITY_TYPE, uploadId,
                        MigrationUploadAuditSnapshot.inFlight(
                                uploadId, upload.getState(), upload.getExpiresAt(),
                                upload.getPrivateKeyCiphertextLength())));
        telemetry.issued(uploadId, clock.instant());
        return MigrationUploadView.of(upload);
    }

    private MigrationUpload insertFreshOrSupersede(UUID userId, Optional<MigrationUpload> prior) {
        MigrationUpload fresh = mintFreshRow(userId);
        if (prior.isPresent()) {
            return supersedeAndPersist(prior.get(), fresh);
        }
        try {
            MigrationUpload saved = repository.save(fresh);
            repository.flush();
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Two-tab race: the loser sees the partial UNIQUE violation.
            // Re-read the now-committed sibling row and supersede it in
            // the same txn.
            MigrationUpload other = repository.findAwaitingByUser(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Constraint violation on insert but no awaiting row to supersede", race));
            // Drop the loser's local entity from the persistence context
            // before re-issuing — Hibernate would otherwise try to flush
            // it again at txn commit.
            entityManager.detach(fresh);
            return supersedeAndPersist(other, mintFreshRow(userId));
        }
    }

    private MigrationUpload supersedeAndPersist(MigrationUpload prior, MigrationUpload fresh) {
        UUID priorId = prior.getRawId();
        MigrationUploadAuditSnapshot priorBefore = MigrationUploadAuditSnapshot.inFlight(
                priorId, prior.getState(), prior.getExpiresAt(),
                prior.getPrivateKeyCiphertextLength());
        prior.supersedeBy(fresh.getRawId(), clock);
        repository.save(prior);
        MigrationUpload saved = repository.save(fresh);
        repository.flush();
        audit.record(AuditAction.MIGRATION_HANDSHAKE_SUPERSEDED,
                AuditedTarget.updated(MigrationUploadAuditSnapshot.AUDIT_ENTITY_TYPE, priorId, priorBefore,
                        MigrationUploadAuditSnapshot.wiped(
                                priorId, prior.getState(), prior.getExpiresAt())));
        telemetry.superseded(priorId, clock.instant());
        return saved;
    }

    private MigrationUpload mintFreshRow(UUID userId) {
        KeyPair keyPair = generateKeyPair();
        // Encoded bytes hold the secret material; crypto.wrap zeroizes the
        // input array in finally. The JCE RSAPrivateKey object retains the
        // modulus/exponent BigIntegers — there is no portable in-process
        // zeroize hook for those without BouncyCastle (out of scope).
        byte[] pkcs8 = keyPair.getPrivate().getEncoded();
        // Generate the row's id locally so the Tink AEAD wrap can bind
        // uploadId.bytes as associatedData. UUID v7 keeps us on the
        // project's time-ordered B-tree-friendly convention.
        UUID uploadId = UuidCreator.getTimeOrderedEpoch();
        byte[] wrapped = crypto.wrap(uploadId, pkcs8);
        return MigrationUpload.issue(uploadId, userId,
                PemEncoders.spkiToPem(keyPair.getPublic()),
                wrapped, clock, HANDSHAKE_TTL);
    }

    @Transactional(readOnly = true)
    public Optional<MigrationUploadView> findCurrent(Jwt jwt) {
        Optional<UUID> userId = userLookup.resolveUserId(jwt);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        return repository.findAwaitingByUser(userId.get()).map(MigrationUploadView::of);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(RSA_KEY_BITS, secureRandom);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK missing RSA KeyPairGenerator", e);
        }
    }

    private static SecureRandom strongRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            // Practically impossible on a supported JRE — surface as
            // IllegalStateException at bean construction rather than
            // silently degrading to the default SecureRandom.
            throw new IllegalStateException("SecureRandom.getInstanceStrong() unavailable", e);
        }
    }
}
