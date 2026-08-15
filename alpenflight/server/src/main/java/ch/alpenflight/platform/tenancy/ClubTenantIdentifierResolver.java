package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class ClubTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    public static final UUID NO_TENANT = new UUID(0L, 0L);

    private final Optional<UserPrincipalLookup> userPrincipalLookup;

    public ClubTenantIdentifierResolver(Optional<UserPrincipalLookup> userPrincipalLookup) {
        this.userPrincipalLookup = userPrincipalLookup;
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContextCarrier.current()
                .or(this::resolveForAuthenticatedPrincipalIgnoringRunAsOverride)
                .orElse(NO_TENANT);
    }

    public Optional<UUID> resolveForAuthenticatedPrincipalIgnoringRunAsOverride() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Jwt jwt = jwtAuth.getToken();

        String claim = jwt.getClaimAsString("clubId");
        if (claim != null && !claim.isBlank()) {
            try {
                return Optional.of(UUID.fromString(claim));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return userPrincipalLookup.flatMap(lookup -> lookup.resolveTenantFor(jwt));
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
