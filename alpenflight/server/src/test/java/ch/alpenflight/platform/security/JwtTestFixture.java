package ch.alpenflight.platform.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only JWT minting fixture. Registers a {@link JwtDecoder} backed by a
 * static RSA keypair and exposes {@link #mint(Consumer)} so tests can sign
 * tokens with arbitrary claims against the same key the decoder validates.
 *
 * <p>The {@code @Primary} {@code JwtDecoder} replaces the production decoder
 * for any {@code @SpringBootTest} that {@code @Import}s this configuration,
 * letting the full Spring Security chain run against synthesised tokens
 * without an OIDC discovery call to Keycloak.
 */
@TestConfiguration
public class JwtTestFixture {

    public static final String TEST_ISSUER = "http://test-issuer";

    private static final RSAKey RSA_KEY = generateKey();

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey(RSA_KEY.toRSAPublicKey())
                    .build();
            OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(TEST_ISSUER);
            decoder.setJwtValidator(validator);
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to derive RSA public key for test JwtDecoder", e);
        }
    }

    public String mint(Consumer<JWTClaimsSet.Builder> customiser) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(TEST_ISSUER)
                .subject("test-user-" + UUID.randomUUID())
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .claim("realm_access", Map.of("roles", List.of("PILOT")));
        customiser.accept(builder);
        return sign(builder.build());
    }

    public String mintWithoutSignature(Consumer<JWTClaimsSet.Builder> customiser) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(TEST_ISSUER)
                .subject("none-alg")
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(Instant.now().plusSeconds(60)));
        customiser.accept(builder);
        JWTClaimsSet claims = builder.build();
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private String sign(JWTClaimsSet claims) {
        try {
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(RSA_KEY.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) RSA_KEY.toPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            var pair = gen.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA generator unavailable", e);
        }
    }

}
