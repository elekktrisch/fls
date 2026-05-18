package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Hibernate {@link CurrentTenantIdentifierResolver} bridging the authenticated
 * principal into the discriminator-based tenancy mechanism (ADR 0008). Spring
 * Boot's {@code BeanContainer} auto-discovers this bean, so no
 * {@code spring.jpa.properties.hibernate.tenant_identifier_resolver} property
 * is needed; pinning the {@code @TenantId} annotation on entity fields is
 * enough to activate discriminator multi-tenancy.
 *
 * <h2>Precedence chain</h2>
 *
 * <ol>
 *   <li>Test seam — {@link TenantTestContextAccess#current()} when present.
 *       Used by integration tests via the S-015 {@code @WithTenant}
 *       extension; never reachable in production because the test-support
 *       package is test-scope only.</li>
 *   <li>Trusted-issuer fast path — when the JWT's {@code iss} is on the
 *       {@link TrustedIssuerRegistry} allowlist, parse the {@code clubId}
 *       claim and return it directly. Signature validation already
 *       authenticated the issuer, so the claim is authoritative.</li>
 *   <li>Federated-issuer DB-verify — when {@code iss} is NOT on the
 *       allowlist, even a present {@code clubId} claim is verified through
 *       {@link UserTenantLookup} against the {@code user} row.</li>
 *   <li>Claim-absent fallback — same {@link UserTenantLookup} path; covers
 *       Google / Auth0 baseline tokens that carry no {@code clubId} claim.</li>
 *   <li>Sentinel — {@link #NO_TENANT}. Fail-closed: tenant-scoped queries
 *       return zero rows; the {@link TenantInsertGuard} blocks writes.</li>
 * </ol>
 *
 * <p>The DB-verify path runs only when needed (federated issuer OR no
 * trusted-issuer claim) and is memoized per request-scoped
 * {@link Authentication} so per-query resolver calls do not fan out into
 * N JDBC hits.
 *
 * <p>Under {@code @Profile("mock-auth")} the {@link UserTenantLookup} bean is
 * absent. The resolver injects {@code Optional.empty()} and skips DB paths;
 * the hardcoded mock principal's {@code clubId} claim is always present, so
 * the trusted-issuer fast path is the only branch exercised.
 */
@Component
public class ClubTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    /**
     * Sentinel returned when no tenant resolves. The nil UUID matches no real
     * {@code club_id} so reads return empty by construction; the
     * {@link TenantInsertGuard} rejects writes whose discriminator is this
     * value. S-023 will add the explicit unscoped-session mechanism.
     */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private static final String CLUB_ID_CLAIM = "clubId";

    private static final Logger LOG = LoggerFactory.getLogger(ClubTenantIdentifierResolver.class);

    private final TrustedIssuerRegistry trustedIssuers;
    private final Optional<UserTenantLookup> userTenantLookup;

    public ClubTenantIdentifierResolver(TrustedIssuerRegistry trustedIssuers,
                                        Optional<UserTenantLookup> userTenantLookup) {
        this.trustedIssuers = trustedIssuers;
        this.userTenantLookup = userTenantLookup;
        TenantInsertGuard.install(this);
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        Optional<UUID> fromTest = TenantTestContextAccess.current();
        if (fromTest.isPresent()) {
            return fromTest.get();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return NO_TENANT;
        }

        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return NO_TENANT;
        }

        Jwt jwt = jwtAuth.getToken();
        Cached cached = (Cached) jwtAuth.getDetails();
        if (cached != null) {
            return cached.tenantId;
        }
        UUID resolved = resolveFrom(jwt);
        jwtAuth.setDetails(new Cached(resolved));
        return resolved;
    }

    private UUID resolveFrom(Jwt jwt) {
        if (trustedIssuers.isTrusted(jwt.getClaimAsString("iss"))) {
            UUID fromClaim = parseClaim(jwt);
            if (fromClaim != null) {
                return fromClaim;
            }
        }
        return userTenantLookup
                .flatMap(lookup -> lookup.resolveTenantFor(jwt))
                .orElse(NO_TENANT);
    }

    private static @Nullable UUID parseClaim(Jwt jwt) {
        String raw = jwt.getClaimAsString(CLUB_ID_CLAIM);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            LOG.warn("clubId claim is not a valid UUID literal sub={}", jwt.getSubject());
            return null;
        }
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    private record Cached(UUID tenantId) {
    }
}
