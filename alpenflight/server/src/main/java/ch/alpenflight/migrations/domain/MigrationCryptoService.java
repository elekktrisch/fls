package ch.alpenflight.migrations.domain;

import java.util.UUID;
import java.util.function.Function;

/**
 * Port over the cryptographic envelope around a per-upload RSA private
 * key. Implemented by {@code ch.alpenflight.migrations.infra} using Google
 * Tink's {@code Aead} primitive (AES-256-GCM) bound to a per-process
 * master {@code KeysetHandle}.
 *
 * <p>The {@code uploadId} UUID is passed as Tink {@code associatedData}
 * so a ciphertext minted for row A cannot be unwrapped against row B's
 * metadata (defense-in-depth against a future code path that loses track
 * of which row a ciphertext belongs to).
 */
public interface MigrationCryptoService {

    /**
     * Wrap the PKCS#8 DER bytes of a per-upload RSA private key under the
     * master keyset. Implementations zeroize the input {@code plaintext}
     * array before returning.
     */
    byte[] wrap(UUID uploadId, byte[] plaintext);

    /**
     * Unwrap a previously-wrapped private-key blob. Throws if the
     * associated-data binding ({@code uploadId}) doesn't match the
     * ciphertext. Caller owns the returned array; for the bundle-decrypt
     * path prefer {@link #unwrapInto} which wipes the array on every exit
     * path.
     */
    byte[] unwrap(UUID uploadId, byte[] ciphertext);

    /**
     * Unwrap into a {@link SecureBytes} closure: the {@link SecureBytes}
     * is opened, handed to {@code work}, then closed in a {@code finally}
     * — the captive byte array is zeroized on every exit (happy, throw,
     * txn-rollback, interrupt). Per the Security plan this is the only
     * path the S-141 ingest pipeline uses; the raw {@link #unwrap} is
     * reserved for unit tests + legacy call sites.
     */
    default <T> T unwrapInto(UUID uploadId,
                             byte[] ciphertext,
                             Function<SecureBytes, T> work) {
        try (SecureBytes secureBytes = new SecureBytes(unwrap(uploadId, ciphertext))) {
            return work.apply(secureBytes);
        }
    }
}
