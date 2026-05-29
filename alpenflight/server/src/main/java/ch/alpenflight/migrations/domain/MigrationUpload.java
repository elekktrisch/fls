package ch.alpenflight.migrations.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.platform.id.MigrationUploadId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Per-upload RSA-4096 handshake row (S-140). Holds the public PEM the
 * user feeds to the legacy export JAR and the wrapped private key the
 * S-141 ingest pipeline unwraps to decrypt the bundle.
 *
 * <p><strong>Pre-tenant.</strong> The row pre-dates any Deployment / Club
 * for the principal (signup → handshake fires before trial Deployment
 * provisioning). No Hibernate {@code @TenantId} — the S-024 leakage
 * sweep is @TenantId-driven and does not touch this table.
 *
 * <p><strong>State machine in Java.</strong> {@link #issue}, {@link #supersedeBy},
 * {@link #markExpired}, {@link #markConsumed}, {@link #markFailed} are
 * the only legal transitions. Per ADR 0022 directive 2 no Postgres CHECK
 * pins the legal set; the FSM is unit-testable here. All four terminal
 * transitions wipe {@link #privateKeyCiphertext} to {@code null}; the
 * forensic question "did we ever hold this private key" is answered by
 * the {@link ch.alpenflight.audit.domain.AuditAction#MIGRATION_HANDSHAKE_ISSUED}
 * audit row, not by the on-disk shape.
 */
@Entity
@Table(name = "t_migration_upload")
public class MigrationUpload {

    /**
     * Caller-supplied id. The Tink AEAD wrap uses {@code uploadId.bytes}
     * as {@code associatedData}, so the service generates the UUID
     * <em>before</em> wrapping — no Hibernate-side {@code @GeneratedValue}.
     * The service uses {@code UuidCreator.getTimeOrderedEpoch()} to stay
     * on the project UUID v7 convention.
     */
    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private MigrationUploadState state;

    @Column(name = "public_key_pem", nullable = false, updatable = false, columnDefinition = "text")
    private String publicKeyPem;

    /**
     * Tink-AEAD wrapped PKCS#8 DER bytes of the per-upload RSA private
     * key. {@link AuditRedact} on the field defends against an audit
     * snapshot of the entity itself ever leaking the ciphertext — the
     * application-layer audit emitter passes a tiny
     * {@code MigrationUploadAuditSnapshot} that records the length, not
     * the bytes; this annotation is the second layer of defense.
     */
    @AuditRedact
    @Column(name = "private_key_ciphertext")
    private byte @Nullable [] privateKeyCiphertext;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private @Nullable Instant consumedAt;

    /** JPA no-arg constructor — do not call from app code. */
    protected MigrationUpload() {
        this.id = new UUID(0, 0);
        this.userId = new UUID(0, 0);
        this.state = MigrationUploadState.AWAITING_UPLOAD;
        this.publicKeyPem = "";
        this.createdAt = Instant.EPOCH;
        this.updatedAt = Instant.EPOCH;
        this.expiresAt = Instant.EPOCH;
    }

    /**
     * Mint a fresh handshake row. Caller wraps the private key under the
     * server master keyset via {@link MigrationCryptoService#wrap} and
     * hands in both the wrapped bytes and the PEM-encoded public key.
     *
     * @param id the pre-generated upload UUID (also the Tink
     *           {@code associatedData} on the wrap); the service mints
     *           a UUID v7 before this call so the binding is consistent.
     */
    public static MigrationUpload issue(UUID id,
                                        UUID userId,
                                        String publicKeyPem,
                                        byte[] wrappedPrivateKey,
                                        Clock clock,
                                        Duration ttl) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(publicKeyPem, "publicKeyPem");
        Objects.requireNonNull(wrappedPrivateKey, "wrappedPrivateKey");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ttl, "ttl");
        if (publicKeyPem.isBlank()) {
            throw new IllegalArgumentException("publicKeyPem must not be blank");
        }
        if (wrappedPrivateKey.length == 0) {
            throw new IllegalArgumentException("wrappedPrivateKey must not be empty");
        }
        MigrationUpload row = new MigrationUpload();
        Instant now = clock.instant();
        row.id = id;
        row.userId = userId;
        row.state = MigrationUploadState.AWAITING_UPLOAD;
        row.publicKeyPem = publicKeyPem;
        row.privateKeyCiphertext = wrappedPrivateKey.clone();
        row.createdAt = now;
        row.updatedAt = now;
        row.expiresAt = now.plus(ttl);
        return row;
    }

    public void supersedeBy(UUID newUploadId, Clock clock) {
        Objects.requireNonNull(newUploadId, "newUploadId");
        if (state != MigrationUploadState.AWAITING_UPLOAD) {
            throw new IllegalUploadStateException(
                    "Cannot supersede a row in state " + state);
        }
        terminalTransition(MigrationUploadState.SUPERSEDED, clock);
    }

    public void markExpired(Clock clock) {
        if (state != MigrationUploadState.AWAITING_UPLOAD) {
            throw new IllegalUploadStateException(
                    "Cannot expire a row in state " + state);
        }
        terminalTransition(MigrationUploadState.EXPIRED, clock);
    }

    /** Used by S-141 when the ingest pipeline rejects the bundle. */
    public void markFailed(Clock clock) {
        if (state != MigrationUploadState.AWAITING_UPLOAD) {
            throw new IllegalUploadStateException(
                    "Cannot fail a row in state " + state);
        }
        terminalTransition(MigrationUploadState.FAILED, clock);
    }

    /** Used by S-141 when the ingest pipeline successfully applies the bundle. */
    public void markConsumed(Clock clock) {
        if (state != MigrationUploadState.AWAITING_UPLOAD) {
            throw new IllegalUploadStateException(
                    "Cannot consume a row in state " + state);
        }
        consumedAt = clock.instant();
        terminalTransition(MigrationUploadState.CONSUMED, clock);
    }

    private void terminalTransition(MigrationUploadState target, Clock clock) {
        state = target;
        updatedAt = clock.instant();
        privateKeyCiphertext = null;
    }

    public MigrationUploadId getId() {
        return MigrationUploadId.of(id);
    }

    /** Raw id accessor for repository / SQL layers; controllers use {@link #getId}. */
    public UUID getRawId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public MigrationUploadState getState() {
        return state;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    /** Returns a defensive copy or {@code null} once the row has left {@code AWAITING_UPLOAD}. */
    public byte @Nullable [] getPrivateKeyCiphertext() {
        return privateKeyCiphertext == null ? null : privateKeyCiphertext.clone();
    }

    public int getPrivateKeyCiphertextLength() {
        return privateKeyCiphertext == null ? 0 : privateKeyCiphertext.length;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public @Nullable Instant getConsumedAt() {
        return consumedAt;
    }
}
