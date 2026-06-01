package ch.alpenflight.migration.bundle.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;

/**
 * PEM-encoding helpers for the migration-handshake flow. Lives in
 * {@code domain} because the encoding contract is part of the public-key
 * surface the SPA + JAR consume; lives in its own file because PEM
 * serialisation isn't an aggregate invariant.
 */
public final class PemEncoders {

    private PemEncoders() {}

    /**
     * RFC 7468 PEM-encoded X.509 SubjectPublicKeyInfo for an RSA
     * public key. 64-character base64 lines, no BouncyCastle dependency.
     */
    public static String spkiToPem(PublicKey publicKey) {
        byte[] spki = publicKey.getEncoded();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(spki);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }
}
