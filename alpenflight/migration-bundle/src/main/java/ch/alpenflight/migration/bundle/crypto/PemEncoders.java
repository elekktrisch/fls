package ch.alpenflight.migration.bundle.crypto;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;

public final class PemEncoders {

    private static final int RFC_7468_PEM_LINE_LENGTH = 64;

    private PemEncoders() {}

    public static String spkiToPem(PublicKey publicKey) {
        byte[] spki = publicKey.getEncoded();
        String base64 = Base64
                .getMimeEncoder(RFC_7468_PEM_LINE_LENGTH, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(spki);
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }
}
