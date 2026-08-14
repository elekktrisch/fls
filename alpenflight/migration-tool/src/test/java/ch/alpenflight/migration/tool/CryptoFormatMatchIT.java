package ch.alpenflight.migration.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.crypto.BundleHeader;
import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.SecureBytes;
import ch.alpenflight.migration.bundle.crypto.TinkMigrationBundleCipher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CryptoFormatMatchIT {

    private final MigrationBundleCipher cipher = new TinkMigrationBundleCipher();

    @Test
    void jar_encrypted_bundle_round_trips_through_the_server_cipher(@TempDir Path dir)
            throws Exception {
        KeyPair rsa = rsa4096();
        UUID uploadId = UUID.randomUUID();

        byte[] plaintext = syntheticPlaintext(50_000);
        Path plaintextFile = dir.resolve("bundle.tar.gz");
        Files.write(plaintextFile, plaintext);

        Path encrypted = dir.resolve("export.enc");
        new BundleEncryptor(cipher).encryptTo(
                encrypted, plaintextFile, uploadId, rsa.getPublic().getEncoded());

        byte[] envelope = Files.readAllBytes(encrypted);

        BundleHeader header = BundleHeader.parse(envelope);
        assertThat(header.version()).isEqualTo(BundleHeader.CURRENT_VERSION);
        assertThat(Arrays.copyOf(envelope, BundleHeader.MAGIC_LENGTH))
                .isEqualTo(BundleHeader.magic());

        byte[] recovered = decrypt(envelope, header, uploadId, rsa);
        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void wrong_upload_id_fails_the_aead_tag(@TempDir Path dir) throws Exception {
        KeyPair rsa = rsa4096();
        UUID encryptUploadId = UUID.randomUUID();
        UUID wrongUploadId = UUID.randomUUID();

        byte[] plaintext = syntheticPlaintext(8_192);
        Path plaintextFile = dir.resolve("bundle.tar.gz");
        Files.write(plaintextFile, plaintext);
        Path encrypted = dir.resolve("export.enc");
        new BundleEncryptor(cipher).encryptTo(
                encrypted, plaintextFile, encryptUploadId, rsa.getPublic().getEncoded());

        byte[] envelope = Files.readAllBytes(encrypted);
        BundleHeader header = BundleHeader.parse(envelope);

        assertThatThrownBy(() -> decrypt(envelope, header, wrongUploadId, rsa))
                .isInstanceOf(IOException.class);
    }

    private byte[] decrypt(byte[] envelope, BundleHeader header, UUID uploadId, KeyPair rsa)
            throws Exception {
        int bodyOffset = BundleHeader.FIXED_PREFIX_BYTES + header.wrappedSessionKey().length;
        byte[] body = Arrays.copyOfRange(envelope, bodyOffset, envelope.length);
        try (SecureBytes session = cipher.unwrapSessionKey(
                     rsa.getPrivate().getEncoded(), header.wrappedSessionKey());
             InputStream plain = cipher.newDecryptingStream(
                     session, uploadId, new ByteArrayInputStream(body))) {
            return plain.readAllBytes();
        }
    }

    private static KeyPair rsa4096() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        return generator.generateKeyPair();
    }

    private static byte[] syntheticPlaintext(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return bytes;
    }
}
