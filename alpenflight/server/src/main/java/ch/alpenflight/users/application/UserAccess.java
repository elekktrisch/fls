package ch.alpenflight.users.application;

import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * SpEL bean wired into {@code @PreAuthorize("@userAccess.canEdit(...)")} on
 * the Users controller's mutating endpoints. Mirrors {@code AircraftAccess}.
 *
 * <p>{@link #canEdit canEdit} is the tenant-scope gate — the controller's
 * {@code hasRole('CLUB_ADMINISTRATOR')} on the same {@code @PreAuthorize}
 * expression is the role-check half. We split the two so a future
 * "sysadmin shadow read" surface wouldn't need to re-implement the tenant
 * predicate.
 *
 * <p>GET endpoints intentionally do NOT use a SpEL gate — the service-layer
 * 404-not-403 IDOR contract requires that cross-tenant reads return 404,
 * but a {@code @PreAuthorize} failure raises 403. Tenant scoping for GETs
 * lives in {@code UsersService.loadInCurrentTenantOrThrow}.
 */
@Component("userAccess")
public class UserAccess {

    private final UserRepository users;

    public UserAccess(UserRepository users) {
        this.users = users;
    }

    public boolean canEdit(UserId id, @Nullable Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        UUID callerClub = resolveCallerClubId(jwt);
        if (callerClub == null) {
            return false;
        }
        return users.findActiveById(id.value())
                .map(User::getClubId)
                .map(callerClub::equals)
                .orElse(false);
    }

    private static @Nullable UUID resolveCallerClubId(Jwt jwt) {
        String raw = jwt.getClaimAsString("clubId");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(raw);
            if (ClubTenantIdentifierResolver.NO_TENANT.equals(parsed)) {
                return null;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
