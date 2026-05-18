package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Hibernate {@link CurrentTenantIdentifierResolver} that maps the
 * authenticated principal to a {@code club_id} value. Spring Boot's
 * {@code BeanContainer} auto-discovers this bean, so no
 * {@code spring.jpa.properties.hibernate.*} keys are needed — pinning
 * {@code @TenantId} on an entity field activates discriminator multi-tenancy.
 *
 * <h2>Precedence</h2>
 *
 * <ol>
 *   <li>{@link TenantTestContextAccess} (test seam — S-015 {@code @WithTenant}).</li>
 *   <li>JWT {@code clubId} claim when present + parseable.</li>
 *   <li>{@link UserTenantLookup} — covers federated users whose tokens
 *       lack a {@code clubId} claim (Google / Auth0). Absent under
 *       {@code @Profile("mock-auth")}.</li>
 *   <li>{@link #NO_TENANT} — the nil UUID. Tenant-scoped reads filter to
 *       zero rows; writes fail at the FK constraint on {@code club_id}.</li>
 * </ol>
 *
 * <p>Spring Security's {@code JwtIssuerValidator} already enforces that the
 * issuer matches the configured {@code spring.security.oauth2.resourceserver
 * .jwt.issuer-uri} before a {@code JwtAuthenticationToken} reaches this
 * resolver — i.e. trusting the claim here is conditioned on Spring having
 * already authenticated the issuer. A future federated-IdP onboarding
 * (multiple simultaneous issuers, Google / Auth0) revisits this contract.
 */
@Component
public class ClubTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    /**
     * Sentinel returned when no tenant resolves. Tenant-scoped reads
     * filter on this value and return zero rows; tenant-scoped writes
     * fail at the {@code fk_<table>_club_id} FK constraint because
     * {@code club} has no nil-UUID row.
     */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private final Optional<UserTenantLookup> userTenantLookup;

    public ClubTenantIdentifierResolver(Optional<UserTenantLookup> userTenantLookup) {
        this.userTenantLookup = userTenantLookup;
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        Optional<UUID> fromTest = TenantTestContextAccess.current();
        if (fromTest.isPresent()) {
            return fromTest.get();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return NO_TENANT;
        }
        Jwt jwt = jwtAuth.getToken();

        String claim = jwt.getClaimAsString("clubId");
        if (claim != null && !claim.isBlank()) {
            try {
                return UUID.fromString(claim);
            } catch (IllegalArgumentException ignored) {
                // fall through to DB lookup
            }
        }

        return userTenantLookup
                .flatMap(lookup -> lookup.resolveTenantFor(jwt))
                .orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
