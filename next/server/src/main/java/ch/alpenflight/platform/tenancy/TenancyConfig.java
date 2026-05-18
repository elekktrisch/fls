package ch.alpenflight.platform.tenancy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link TrustedIssuerRegistry} from configuration. The allowlist
 * defaults to a single entry — the configured OIDC issuer (our Keycloak in
 * dev, our hosted IdP in prod) — so the trusted-issuer fast path Just Works
 * out of the box. Operators add federated issuers by setting the
 * {@code ALPENFLIGHT_AUTH_TRUSTED_ISSUERS} env var (comma-separated) or a
 * {@code alpenflight.auth.trusted-issuers} list in YAML.
 *
 * <p>Wired here (not as a {@code @ConfigurationProperties} record) so a
 * federated-issuer extension can be added incrementally without breaking
 * the default seeding from the existing OIDC issuer property.
 */
@Configuration
public class TenancyConfig {

    @Bean
    TrustedIssuerRegistry trustedIssuerRegistry(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String defaultIssuer,
            @Value("${alpenflight.auth.trusted-issuers:}") List<String> configured) {
        List<String> issuers = new ArrayList<>();
        if (configured != null) {
            issuers.addAll(configured.stream().filter(s -> !s.isBlank()).toList());
        }
        if (issuers.isEmpty() && defaultIssuer != null && !defaultIssuer.isBlank()) {
            issuers.add(defaultIssuer);
        }
        return new TrustedIssuerRegistry(issuers);
    }
}
