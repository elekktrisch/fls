package ch.alpenflight.migrations.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.crypto.PemEncoders;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MigrationPemRoundTripTest {

    private static final int RSA_KEY_BITS = 4096;

    @Test
    void rsa_4096_public_pem_round_trips_through_KeyFactory_and_has_expected_modulus() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(RSA_KEY_BITS);
        KeyPair pair = gen.generateKeyPair();

        // Modulus exposes the strength contract.
        assertThat(((RSAPublicKey) pair.getPublic()).getModulus().bitLength())
                .isEqualTo(RSA_KEY_BITS);

        String pem = PemEncoders.spkiToPem(pair.getPublic());
        assertThat(pem).startsWith("-----BEGIN PUBLIC KEY-----\n");
        assertThat(pem).endsWith("\n-----END PUBLIC KEY-----\n");

        // Standard PEM-decode: strip headers + newlines, base64-decode the rest.
        String base64Body = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] spki = Base64.getDecoder().decode(base64Body);
        PublicKey restored = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(spki));
        assertThat(restored).isEqualTo(pair.getPublic());
    }

    @Test
    void pem_line_length_caps_at_64_characters() {
        KeyPairGenerator gen;
        try {
            gen = KeyPairGenerator.getInstance("RSA");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        String pem = PemEncoders.spkiToPem(pair.getPublic());

        for (String line : pem.split("\n", -1)) {
            assertThat(line.length()).isLessThanOrEqualTo(64);
        }
    }
}
