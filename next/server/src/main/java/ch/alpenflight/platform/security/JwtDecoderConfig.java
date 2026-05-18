package ch.alpenflight.platform.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Programmatic {@link JwtDecoder} for the split-port Keycloak topology —
 * {@code jwk-set-uri} is the compose-network URL the JVM uses to fetch keys
 * and {@code issuer-uri} is the host URL baked into every token's {@code iss}
 * claim. Spring Boot's auto-config can only carry one URL; this bean
 * suppresses it under every profile (including {@code mock-auth} where the
 * decoder is never invoked) so an unreachable issuer doesn't break the
 * application context.
 *
 * <p>Audience validation is intentionally absent — per ADR 0007 the
 * production IdP is TBD (Google / Ory / Auth0 / self-hosted Keycloak), and
 * locking the validator chain to a Keycloak-specific audience mapper would
 * forfeit vendor portability. {@link JwtIssuerValidator} already attests the
 * token's origin; user-to-tenant authorization is the {@code @TenantId}
 * resolver's concern (S-022, claim-first with DB fallback).
 *
 * <p>The 60s timestamp skew tolerates ±NTP drift between Keycloak's container
 * clock and the host JVM — zero skew rejects valid tokens on a cold dev
 * laptop; higher values become replay-window slack.
 */
@Configuration
class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(60)),
                new JwtIssuerValidator(issuerUri));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
