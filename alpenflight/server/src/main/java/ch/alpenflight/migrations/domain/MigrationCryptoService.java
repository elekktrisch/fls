package ch.alpenflight.migrations.domain;

import java.util.UUID;

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
     * ciphertext.
     */
    byte[] unwrap(UUID uploadId, byte[] ciphertext);
}
