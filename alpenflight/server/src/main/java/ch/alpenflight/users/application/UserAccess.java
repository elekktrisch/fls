package ch.alpenflight.users.application;

import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * SpEL bean wired into {@code @PreAuthorize("@userAccess.canEdit(...)")}
 * expressions on the Users controller. Mirrors {@code AircraftAccess}.
 *
 * <ul>
 *   <li>{@link #canView canView} — load by id is allowed when the user
 *       belongs to the caller's home club. Returns {@code false} otherwise
 *       so the service-layer 404 keeps the existence opaque.</li>
 *   <li>{@link #canEdit canEdit} — same predicate as {@code canView} plus
 *       the role check (CLUB_ADMINISTRATOR). The {@code @PreAuthorize}
 *       expression usually combines this with {@code hasRole(...)} too —
 *       we don't trust either gate alone.</li>
 * </ul>
 *
 * <p>Sysadmin is intentionally NOT admitted here. Per the S-052
 * architectural rule, sysadmin manages clubs, not users.
 */
@Component("userAccess")
public class UserAccess {

    private final UserRepository users;

    public UserAccess(UserRepository users) {
        this.users = users;
    }

    public boolean canView(UserId id, @Nullable Jwt jwt) {
        return sameClub(id, jwt);
    }

    public boolean canEdit(UserId id, @Nullable Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        if (!hasRealmRole(jwt, "CLUB_ADMINISTRATOR")) {
            return false;
        }
        return sameClub(id, jwt);
    }

    private boolean sameClub(UserId id, @Nullable Jwt jwt) {
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

    private static boolean hasRealmRole(Jwt jwt, String name) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> ra)) {
            return false;
        }
        Object roles = ra.get("roles");
        if (!(roles instanceof Collection<?> raw)) {
            return false;
        }
        for (Object role : raw) {
            if (role instanceof String s && name.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
