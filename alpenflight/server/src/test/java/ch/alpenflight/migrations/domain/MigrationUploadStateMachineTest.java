package ch.alpenflight.migrations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationUploadStateMachineTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PEM = "-----BEGIN PUBLIC KEY-----\nXXXX\n-----END PUBLIC KEY-----\n";
    private static final byte[] WRAPPED = new byte[] {1, 2, 3, 4, 5};
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-05-29T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void issue_starts_in_awaiting_upload_with_ttl_relative_to_clock() {
        MigrationUpload upload = MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, WRAPPED, CLOCK, Duration.ofHours(24));

        assertThat(upload.getState()).isEqualTo(MigrationUploadState.AWAITING_UPLOAD);
        assertThat(upload.getUserId()).isEqualTo(USER_ID);
        assertThat(upload.getPublicKeyPem()).isEqualTo(PEM);
        assertThat(upload.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(upload.getExpiresAt()).isEqualTo(CLOCK.instant().plusSeconds(24 * 3600));
        assertThat(upload.getPrivateKeyCiphertextLength()).isEqualTo(WRAPPED.length);
        WRAPPED[0] = 99;
        assertThat(upload.getPrivateKeyCiphertext()).startsWith((byte) 1);
    }

    @Test
    void supersedeBy_flips_to_superseded_and_wipes_key() {
        MigrationUpload upload = MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, new byte[] {1, 2}, CLOCK, Duration.ofHours(24));

        upload.supersedeBy(UUID.randomUUID(), CLOCK);

        assertThat(upload.getState()).isEqualTo(MigrationUploadState.SUPERSEDED);
        assertThat(upload.getPrivateKeyCiphertext()).isNull();
        assertThat(upload.getPrivateKeyCiphertextLength()).isZero();
    }

    @Test
    void markExpired_flips_to_expired_and_wipes_key() {
        MigrationUpload upload = MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, new byte[] {1, 2}, CLOCK, Duration.ofHours(24));

        upload.markExpired(CLOCK);

        assertThat(upload.getState()).isEqualTo(MigrationUploadState.EXPIRED);
        assertThat(upload.getPrivateKeyCiphertext()).isNull();
    }

    @Test
    void markConsumed_records_consumedAt_and_wipes_key() {
        MigrationUpload upload = MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, new byte[] {1, 2}, CLOCK, Duration.ofHours(24));

        upload.markConsumed(CLOCK);

        assertThat(upload.getState()).isEqualTo(MigrationUploadState.CONSUMED);
        assertThat(upload.getConsumedAt()).isEqualTo(CLOCK.instant());
        assertThat(upload.getPrivateKeyCiphertext()).isNull();
    }

    @Test
    void markFailed_wipes_key_without_setting_consumedAt() {
        MigrationUpload upload = MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, new byte[] {1, 2}, CLOCK, Duration.ofHours(24));

        upload.markFailed(CLOCK);

        assertThat(upload.getState()).isEqualTo(MigrationUploadState.FAILED);
        assertThat(upload.getConsumedAt()).isNull();
        assertThat(upload.getPrivateKeyCiphertext()).isNull();
    }

    @Test
    void terminal_state_refuses_further_transitions() {
        MigrationUpload upload = MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, new byte[] {1, 2}, CLOCK, Duration.ofHours(24));
        upload.markExpired(CLOCK);

        assertThatThrownBy(() -> upload.supersedeBy(UUID.randomUUID(), CLOCK))
                .isInstanceOf(IllegalUploadStateException.class);
        assertThatThrownBy(() -> upload.markExpired(CLOCK))
                .isInstanceOf(IllegalUploadStateException.class);
        assertThatThrownBy(() -> upload.markConsumed(CLOCK))
                .isInstanceOf(IllegalUploadStateException.class);
        assertThatThrownBy(() -> upload.markFailed(CLOCK))
                .isInstanceOf(IllegalUploadStateException.class);
    }

    @Test
    void issue_rejects_blank_pem() {
        assertThatThrownBy(() -> MigrationUpload.issue(UUID.randomUUID(), USER_ID, "", new byte[] {1}, CLOCK, Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publicKeyPem");
    }

    @Test
    void issue_rejects_empty_wrapped_key() {
        assertThatThrownBy(() -> MigrationUpload.issue(UUID.randomUUID(), USER_ID, PEM, new byte[0], CLOCK, Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wrappedPrivateKey");
    }
}
