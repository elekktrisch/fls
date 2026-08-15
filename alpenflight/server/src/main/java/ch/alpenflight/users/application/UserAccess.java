package ch.alpenflight.users.application;

import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

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
