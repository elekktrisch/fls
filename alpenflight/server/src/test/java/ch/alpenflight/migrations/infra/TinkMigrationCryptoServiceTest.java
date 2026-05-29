package ch.alpenflight.migrations.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TinkMigrationCryptoServiceTest {

    private static Aead aead;

    @BeforeAll
    static void setUpAead() throws GeneralSecurityException {
        AeadConfig.register();
        KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
        aead = handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
    }

    @Test
    void wrap_then_unwrap_with_same_upload_id_round_trips() {
        TinkMigrationCryptoService crypto = new TinkMigrationCryptoService(aead);
        UUID uploadId = UUID.randomUUID();
        byte[] plain = "secret-private-key".getBytes(StandardCharsets.UTF_8);

        byte[] wrapped = crypto.wrap(uploadId, plain.clone());

        byte[] unwrapped = crypto.unwrap(uploadId, wrapped);
        assertThat(new String(unwrapped)).isEqualTo("secret-private-key");
    }

    @Test
    void unwrap_with_wrong_upload_id_throws() {
        TinkMigrationCryptoService crypto = new TinkMigrationCryptoService(aead);
        UUID issuedFor = UUID.randomUUID();
        UUID otherUpload = UUID.randomUUID();
        byte[] wrapped = crypto.wrap(issuedFor, "secret".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> crypto.unwrap(otherUpload, wrapped))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tink unwrap failed");
    }

    @Test
    void wrap_zeroizes_input_plaintext() {
        TinkMigrationCryptoService crypto = new TinkMigrationCryptoService(aead);
        byte[] plaintext = "very-secret".getBytes(StandardCharsets.UTF_8);
        byte[] originalCopy = plaintext.clone();

        crypto.wrap(UUID.randomUUID(), plaintext);

        assertThat(plaintext).isNotEqualTo(originalCopy);
        for (byte b : plaintext) {
            assertThat(b).isZero();
        }
    }
}
