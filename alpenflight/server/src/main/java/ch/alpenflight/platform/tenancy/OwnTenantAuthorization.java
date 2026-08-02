package ch.alpenflight.platform.tenancy;

import ch.alpenflight.platform.id.ClubId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * SpEL-callable own-tenant predicate for {@code @PreAuthorize}, registered as
 * {@code @tenant} — e.g.
 * {@code @PreAuthorize("hasRole('CLUB_ADMINISTRATOR') and @tenant.isOwnClub(#id)")}.
 *
 * <p>The gate compares a path-variable club against the tenant
 * {@link ClubTenantIdentifierResolver} resolves for the principal, which is the
 * same value {@code @TenantId} filters every tenant-scoped read on. A gate that
 * instead compared the raw {@code clubId} claim string would disagree with that
 * resolution for every principal whose claim is a club key rather than a UUID.
 *
 * <p>Fail-closed: an unauthenticated principal, an unresolvable one, and the
 * {@link ClubTenantIdentifierResolver#NO_TENANT} sentinel all deny.
 */
@Component("tenant")
public class OwnTenantAuthorization {

    private final ClubTenantIdentifierResolver resolver;

    public OwnTenantAuthorization(ClubTenantIdentifierResolver resolver) {
        this.resolver = resolver;
    }

    public boolean isOwnClub(@Nullable ClubId clubId) {
        if (clubId == null) {
            return false;
        }
        UUID target = clubId.value();
        return resolver.resolveForAuthenticatedPrincipal()
                .filter(tenant -> !ClubTenantIdentifierResolver.NO_TENANT.equals(tenant))
                .filter(target::equals)
                .isPresent();
    }
}
