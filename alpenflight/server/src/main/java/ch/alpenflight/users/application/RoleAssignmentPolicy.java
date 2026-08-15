package ch.alpenflight.users.application;

import ch.alpenflight.users.domain.Role;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class RoleAssignmentPolicy {

    private static final Set<Role> CLUB_ADMIN_GRANTABLE_NEVER_SYSTEM_ADMINISTRATOR = Set.of(
            Role.CLUB_ADMINISTRATOR,
            Role.FLIGHT_OPERATOR,
            Role.PILOT,
            Role.OFFICE_USER,
            Role.GUEST);

    public boolean isGrantable(@org.jspecify.annotations.Nullable Jwt callerJwt, Collection<Role> targetRoles) {
        if (callerJwt == null) {
            return false;
        }
        Set<Role> callerRoles = readRealmRoles(callerJwt);
        if (callerRoles.contains(Role.SYSTEM_ADMINISTRATOR)) {
            return false;
        }
        if (!callerRoles.contains(Role.CLUB_ADMINISTRATOR)) {
            return false;
        }
        return targetRoles.stream().allMatch(CLUB_ADMIN_GRANTABLE_NEVER_SYSTEM_ADMINISTRATOR::contains);
    }

    public Set<Role> rejectedRoles(@org.jspecify.annotations.Nullable Jwt callerJwt, Collection<Role> targetRoles) {
        Set<Role> callerRoles = callerJwt == null ? Set.of() : readRealmRoles(callerJwt);
        if (callerRoles.contains(Role.CLUB_ADMINISTRATOR)
                && !callerRoles.contains(Role.SYSTEM_ADMINISTRATOR)) {
            return targetRoles.stream()
                    .filter(r -> !CLUB_ADMIN_GRANTABLE_NEVER_SYSTEM_ADMINISTRATOR.contains(r))
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.copyOf(targetRoles);
    }

    private static Set<Role> readRealmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> ra)) {
            return Set.of();
        }
        Object roles = ra.get("roles");
        if (!(roles instanceof Collection<?> raw)) {
            return Set.of();
        }
        return raw.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(Role::fromWire)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
