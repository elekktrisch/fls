package ch.alpenflight.platform.tenancy;

import ch.alpenflight.platform.id.ClubId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

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
