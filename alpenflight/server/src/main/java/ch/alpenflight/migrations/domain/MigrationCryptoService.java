package ch.alpenflight.migrations.domain;

import ch.alpenflight.migration.bundle.crypto.SecureBytes;
import java.util.UUID;
import java.util.function.Function;

public interface MigrationCryptoService {

    byte[] wrap(UUID uploadId, byte[] plaintext);

    byte[] unwrap(UUID uploadId, byte[] ciphertext);

    default <T> T unwrapInto(UUID uploadId,
                             byte[] ciphertext,
                             Function<SecureBytes, T> work) {
        try (SecureBytes secureBytes = new SecureBytes(unwrap(uploadId, ciphertext))) {
            return work.apply(secureBytes);
        }
    }
}
