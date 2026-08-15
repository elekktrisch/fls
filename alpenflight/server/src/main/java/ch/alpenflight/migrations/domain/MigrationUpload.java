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

@Entity
@Table(name = "t_migration_upload")
public class MigrationUpload {

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

    protected MigrationUpload() {
        this.id = new UUID(0, 0);
        this.userId = new UUID(0, 0);
        this.state = MigrationUploadState.AWAITING_UPLOAD;
        this.publicKeyPem = "";
        this.createdAt = Instant.EPOCH;
        this.updatedAt = Instant.EPOCH;
        this.expiresAt = Instant.EPOCH;
    }

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

    public void markFailed(Clock clock) {
        if (state != MigrationUploadState.AWAITING_UPLOAD) {
            throw new IllegalUploadStateException(
                    "Cannot fail a row in state " + state);
        }
        terminalTransition(MigrationUploadState.FAILED, clock);
    }

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
