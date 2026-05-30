package ch.alpenflight.migrations.domain;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Port over the bundle-level cryptographic envelope (S-141). Distinct
 * from {@link MigrationCryptoService} (which wraps the per-upload RSA
 * private key under the server master keyset): this port owns the
 * <em>body</em> path — RSA-OAEP unwrap of the ephemeral AES-256 session
 * key + Tink StreamingAead framed body decrypt.
 *
 * <p>Implementations bind the upload UUID as Tink {@code associatedData}
 * so a body recovered through one upload's session key cannot be replayed
 * against another upload's container.
 */
public interface MigrationBundleCipher {

    /**
     * Unwraps the ephemeral AES-256 session key from the bundle header.
     *
     * @param rsaPrivateKeyPkcs8 the per-upload RSA private key (PKCS#8 DER).
     *                           Caller owns the array; this port does NOT
     *                           zeroize it.
     * @param wrappedSessionKey  the RSA-OAEP-SHA256-wrapped 32-byte AES-256
     *                           session key from {@link BundleHeader#wrappedSessionKey}.
     * @return a {@link SecureBytes} holding the raw 32-byte session key.
     *         Caller wraps in try-with-resources so the array zeros on
     *         every exit path.
     */
    SecureBytes unwrapSessionKey(byte[] rsaPrivateKeyPkcs8, byte[] wrappedSessionKey);

    /**
     * Returns a decrypting {@link InputStream} that consumes the framed
     * Tink StreamingAead body off {@code ciphertextBody} and yields the
     * gzip-tar plaintext. {@code uploadId.bytes} is bound as Tink
     * {@code associatedData}.
     *
     * <p>The returned stream is single-use. Closing it propagates the
     * close to {@code ciphertextBody}.
     */
    InputStream newDecryptingStream(SecureBytes sessionKey,
                                    UUID uploadId,
                                    InputStream ciphertextBody);

    /**
     * Returns an encrypting {@link OutputStream} that consumes plaintext
     * (gzip-tar bytes) and writes a framed Tink StreamingAead body to
     * {@code ciphertextSink}. Pair with {@link #wrapSessionKey} to produce
     * a complete bundle (test producer + S-139 path).
     */
    OutputStream newEncryptingStream(SecureBytes sessionKey,
                                     UUID uploadId,
                                     OutputStream ciphertextSink);

    /**
     * Wrap a raw 32-byte AES session key under the recipient's RSA-4096
     * public key (RSA-OAEP-SHA256). Bundle producers (S-139 JAR + test
     * producer) call this; the server uses {@link #unwrapSessionKey}.
     */
    byte[] wrapSessionKey(byte[] rsaPublicKeyDer, byte[] sessionKey);
}
