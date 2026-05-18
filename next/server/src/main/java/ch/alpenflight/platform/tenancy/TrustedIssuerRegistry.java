package ch.alpenflight.platform.tenancy;

import java.util.List;
import java.util.Set;

/**
 * Allowlist of OIDC issuers whose {@code clubId} claim is trusted directly
 * (no DB-verify). Wired in {@link TenancyConfig} from
 * {@code alpenflight.auth.trusted-issuers} (preferred) or, as a default,
 * the configured {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
 * When a federated IdP (Google, Auth0) ever issues for AlpenFlight, the
 * operator either adds it to the allowlist (claim trusted) or leaves it out
 * (DB-verify forces every claim through a {@code user} row lookup).
 */
public class TrustedIssuerRegistry {

    private final Set<String> trustedIssuers;

    public TrustedIssuerRegistry(List<String> trustedIssuers) {
        this.trustedIssuers = trustedIssuers == null ? Set.of() : Set.copyOf(trustedIssuers);
    }

    public boolean isTrusted(String issuer) {
        return issuer != null && trustedIssuers.contains(issuer);
    }

    public Set<String> trustedIssuers() {
        return trustedIssuers;
    }
}
