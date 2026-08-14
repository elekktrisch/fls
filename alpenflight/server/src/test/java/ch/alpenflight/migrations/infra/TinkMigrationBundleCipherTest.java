package ch.alpenflight.migrations.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.SecureBytes;
import ch.alpenflight.migration.bundle.crypto.TinkMigrationBundleCipher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TinkMigrationBundleCipherTest {

    private static MigrationBundleCipher cipher;
    private static KeyPair rsaKeyPair;
    private static byte[] rsaPublicKeyDer;
    private static byte[] rsaPrivateKeyPkcs8;

    @BeforeAll
    static void setUp() throws Exception {
        cipher = new TinkMigrationBundleCipher();
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(4096, new SecureRandom());
        rsaKeyPair = gen.generateKeyPair();
        rsaPublicKeyDer = rsaKeyPair.getPublic().getEncoded();
        rsaPrivateKeyPkcs8 = rsaKeyPair.getPrivate().getEncoded();
    }

    @Test
    void encrypt_decrypt_round_trip_preserves_bytes() throws Exception {
        UUID uploadId = UUID.randomUUID();
        byte[] sessionKey = new byte[32];
        new SecureRandom().nextBytes(sessionKey);
        byte[] wrappedSessionKey = cipher.wrapSessionKey(rsaPublicKeyDer, sessionKey);
        byte[] plaintext = "the quick brown fox jumps over the lazy dog".repeat(200).getBytes();

        ByteArrayOutputStream ciphertextSink = new ByteArrayOutputStream();
        try (SecureBytes sessionBytes = new SecureBytes(sessionKey.clone());
             OutputStream encrypting = cipher.newEncryptingStream(sessionBytes, uploadId, ciphertextSink)) {
            encrypting.write(plaintext);
        }
        byte[] ciphertext = ciphertextSink.toByteArray();
        assertThat(ciphertext.length).isGreaterThan(plaintext.length);

        ByteArrayOutputStream recovered = new ByteArrayOutputStream();
        try (SecureBytes recoveredSessionKey = cipher.unwrapSessionKey(rsaPrivateKeyPkcs8, wrappedSessionKey);
             InputStream decrypting = cipher.newDecryptingStream(
                     recoveredSessionKey, uploadId, new ByteArrayInputStream(ciphertext))) {
            decrypting.transferTo(recovered);
        }
        assertThat(recovered.toByteArray()).isEqualTo(plaintext);
        assertThat(((RSAPublicKey) rsaKeyPair.getPublic()).getModulus().bitLength()).isEqualTo(4096);
    }

    @Test
    void decrypt_then_gzip_then_tar_round_trip() throws Exception {
        UUID uploadId = UUID.randomUUID();
        byte[] sessionKey = new byte[32];
        new SecureRandom().nextBytes(sessionKey);
        byte[] wrappedSessionKey = cipher.wrapSessionKey(rsaPublicKeyDer, sessionKey);

        byte[] manifestPayload = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream tarGzPlaintextSink = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(tarGzPlaintextSink);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            TarArchiveEntry entry = new TarArchiveEntry("manifest.json");
            entry.setSize(manifestPayload.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(manifestPayload);
            tarOut.closeArchiveEntry();
            tarOut.finish();
        }
        byte[] tarGzPlaintext = tarGzPlaintextSink.toByteArray();

        ByteArrayOutputStream ciphertextSink = new ByteArrayOutputStream();
        try (SecureBytes sessionBytes = new SecureBytes(sessionKey.clone());
             OutputStream encrypting = cipher.newEncryptingStream(sessionBytes, uploadId, ciphertextSink)) {
            encrypting.write(tarGzPlaintext);
        }
        byte[] ciphertext = ciphertextSink.toByteArray();

        byte[] recoveredManifest;
        try (SecureBytes recoveredSessionKey = cipher.unwrapSessionKey(rsaPrivateKeyPkcs8, wrappedSessionKey);
             InputStream decrypting = cipher.newDecryptingStream(
                     recoveredSessionKey, uploadId, new ByteArrayInputStream(ciphertext));
             GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(decrypting);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
            TarArchiveEntry firstEntry = tarIn.getNextEntry();
            assertThat(firstEntry).isNotNull();
            assertThat(firstEntry.getName()).isEqualTo("manifest.json");
            recoveredManifest = tarIn.readAllBytes();
            assertThat(tarIn.getNextEntry()).as("only one entry expected").isNull();
        }
        assertThat(recoveredManifest).isEqualTo(manifestPayload);
    }
}
