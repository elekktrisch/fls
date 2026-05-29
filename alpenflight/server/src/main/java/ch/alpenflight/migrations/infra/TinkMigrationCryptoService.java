package ch.alpenflight.migrations.infra;

import ch.alpenflight.migrations.domain.MigrationCryptoService;
import com.google.crypto.tink.Aead;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tink-AEAD adapter. The {@code uploadId} UUID is encoded as 16 bytes and
 * passed as {@code associatedData} so a ciphertext minted for row A
 * cannot be unwrapped against row B's metadata.
 *
 * <p>Plaintext byte arrays are zeroized in-place after wrapping; the caller
 * is expected to pass an array it no longer references.
 */
@Component
class TinkMigrationCryptoService implements MigrationCryptoService {

    private final Aead aead;

    TinkMigrationCryptoService(Aead migrationMasterKeyAead) {
        this.aead = migrationMasterKeyAead;
    }

    @Override
    public byte[] wrap(UUID uploadId, byte[] plaintext) {
        byte[] associatedData = uuidBytes(uploadId);
        try {
            return aead.encrypt(plaintext, associatedData);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Tink wrap failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public byte[] unwrap(UUID uploadId, byte[] ciphertext) {
        try {
            return aead.decrypt(ciphertext, uuidBytes(uploadId));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Tink unwrap failed for upload " + uploadId, e);
        }
    }

    private static byte[] uuidBytes(UUID id) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        return buf.array();
    }
}
