package ch.alpenflight.migrations.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migrations.domain.MigrationBundleCipher;
import ch.alpenflight.migrations.domain.SecureBytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit-level round-trip for the streaming bundle cipher. Generates an
 * RSA-4096 keypair + a random 32-byte session key in-process; verifies
 * encrypt → decrypt yields the original bytes.
 */
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
}
