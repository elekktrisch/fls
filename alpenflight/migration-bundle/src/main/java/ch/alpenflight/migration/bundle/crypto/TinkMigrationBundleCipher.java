package ch.alpenflight.migration.bundle.crypto;

import com.google.crypto.tink.AccessesPartialKey;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.StreamingAead;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey;
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters;
import com.google.crypto.tink.streamingaead.StreamingAeadConfig;
import com.google.crypto.tink.util.SecretBytes;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Tink-backed implementation of {@link MigrationBundleCipher}. Tink owns
 * the streaming AEAD body; the JDK {@link Cipher} provider owns the
 * one-shot RSA-OAEP session-key wrap (no framed input, no truncation risk).
 *
 * <p>Streaming parameters: {@code AES256_GCM_HKDF_4KB} (4 KB ciphertext
 * segments, SHA-256 HKDF derivation, 32-byte derived key). The producer
 * (S-139 JAR + test producer in {@code MigrationBundleTestFactory}) uses
 * the same parameters; mismatch surfaces as
 * {@link BundleCipherException.Failure#AEAD_TAG_FAILED} on the
 * first segment read.
 */
public class TinkMigrationBundleCipher implements MigrationBundleCipher {

    public TinkMigrationBundleCipher() {
    }

    private static final int AES_KEY_BYTES = 32;
    private static final int RSA_KEY_BITS = 4096;
    private static final int CIPHERTEXT_SEGMENT_BYTES = 4096;
    private static final String RSA_OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    static {
        try {
            StreamingAeadConfig.register();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Tink StreamingAeadConfig.register() failed", e);
        }
    }

    @Override
    public SecureBytes unwrapSessionKey(byte[] rsaPrivateKeyPkcs8, byte[] wrappedSessionKey) {
        try {
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(rsaPrivateKeyPkcs8));
            Cipher cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepParameterSpec());
            byte[] sessionKey = cipher.doFinal(wrappedSessionKey);
            if (sessionKey.length != AES_KEY_BYTES) {
                java.util.Arrays.fill(sessionKey, (byte) 0);
                throw new BundleCipherException(
                        BundleCipherException.Failure.RSA_UNWRAP_FAILED,
                        "Unwrapped session key must be " + AES_KEY_BYTES + " bytes, got " + sessionKey.length);
            }
            return new SecureBytes(sessionKey);
        } catch (GeneralSecurityException e) {
            throw new BundleCipherException(
                    BundleCipherException.Failure.RSA_UNWRAP_FAILED,
                    "RSA-OAEP session-key unwrap failed", e);
        }
    }

    @Override
    public InputStream newDecryptingStream(SecureBytes sessionKey, UUID uploadId, InputStream ciphertextBody) {
        try {
            StreamingAead streamingAead = streamingAeadFor(sessionKey);
            return streamingAead.newDecryptingStream(ciphertextBody, uuidBytes(uploadId));
        } catch (GeneralSecurityException | IOException e) {
            throw new BundleCipherException(
                    BundleCipherException.Failure.AEAD_TAG_FAILED,
                    "Failed to build StreamingAead decrypting stream", e);
        }
    }

    @Override
    public OutputStream newEncryptingStream(SecureBytes sessionKey, UUID uploadId, OutputStream ciphertextSink) {
        try {
            StreamingAead streamingAead = streamingAeadFor(sessionKey);
            return streamingAead.newEncryptingStream(ciphertextSink, uuidBytes(uploadId));
        } catch (GeneralSecurityException | IOException e) {
            throw new BundleCipherException(
                    BundleCipherException.Failure.INTERNAL,
                    "Failed to build StreamingAead encrypting stream (producer side)", e);
        }
    }

    @Override
    public byte[] wrapSessionKey(byte[] rsaPublicKeyDer, byte[] sessionKey) {
        if (sessionKey.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "sessionKey must be " + AES_KEY_BYTES + " bytes, got " + sessionKey.length);
        }
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(rsaPublicKeyDer));
            if (((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength() != RSA_KEY_BITS) {
                throw new IllegalArgumentException("RSA public key must be " + RSA_KEY_BITS + " bits");
            }
            Cipher cipher = Cipher.getInstance(RSA_OAEP_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParameterSpec());
            return cipher.doFinal(sessionKey);
        } catch (GeneralSecurityException e) {
            throw new BundleCipherException(
                    BundleCipherException.Failure.INTERNAL,
                    "RSA-OAEP session-key wrap failed (producer side)", e);
        }
    }

    @AccessesPartialKey
    private static StreamingAead streamingAeadFor(SecureBytes sessionKey) throws GeneralSecurityException {
        AesGcmHkdfStreamingParameters parameters = AesGcmHkdfStreamingParameters.builder()
                .setKeySizeBytes(AES_KEY_BYTES)
                .setDerivedAesGcmKeySizeBytes(AES_KEY_BYTES)
                .setCiphertextSegmentSizeBytes(CIPHERTEXT_SEGMENT_BYTES)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .build();
        AesGcmHkdfStreamingKey key = AesGcmHkdfStreamingKey.create(
                parameters,
                SecretBytes.copyFrom(sessionKey.bytes(), InsecureSecretKeyAccess.get()));
        KeysetHandle handle = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary())
                .build();
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead.class);
    }

    private static OAEPParameterSpec oaepParameterSpec() {
        return new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    private static byte[] uuidBytes(UUID id) {
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(id.getMostSignificantBits());
        buf.putLong(id.getLeastSignificantBits());
        return buf.array();
    }
}
