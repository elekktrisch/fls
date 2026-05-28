package ch.alpenflight.users.application;

import ch.alpenflight.users.domain.Role;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Server-side privilege-escalation gate: CLUB_ADMIN may grant
 * {@code {CLUB_ADMINISTRATOR, FLIGHT_OPERATOR, PILOT, OFFICE_USER, GUEST}} —
 * never {@code SYSTEM_ADMINISTRATOR}.
 *
 * <p>Per the security plan threat-model row: the DTO {@code @Pattern} alone
 * is not enough — a CLUB_ADMIN with a hand-crafted payload could enumerate
 * realm-management roles. This policy is the load-bearing rejection at the
 * service layer; the {@code USER_ROLE_GRANT_REJECTED} audit row is emitted
 * by the caller (UsersService) on every refusal.
 */
@Component
public class RoleAssignmentPolicy {

    private static final Set<Role> CLUB_ADMIN_GRANTABLE = Set.of(
            Role.CLUB_ADMINISTRATOR,
            Role.FLIGHT_OPERATOR,
            Role.PILOT,
            Role.OFFICE_USER,
            Role.GUEST);

    /**
     * Returns {@code true} iff every role in {@code targetRoles} is grantable
     * by the caller. The caller is assumed to already hold
     * {@code CLUB_ADMINISTRATOR} (the {@code @PreAuthorize} on the endpoint
     * enforces that); this method narrows what they can grant.
     */
    public boolean isGrantable(@org.jspecify.annotations.Nullable Jwt callerJwt, Collection<Role> targetRoles) {
        if (callerJwt == null) {
            return false;
        }
        Set<Role> callerRoles = readRealmRoles(callerJwt);
        if (callerRoles.contains(Role.SYSTEM_ADMINISTRATOR)) {
            // Sysadmin grants are out-of-scope for /api/v1/users; the
            // architectural rule denies the endpoint entirely. Falling
            // through to "deny" here is the right behavior — sysadmin
            // shouldn't be reaching this controller in the first place.
            return false;
        }
        if (!callerRoles.contains(Role.CLUB_ADMINISTRATOR)) {
            return false;
        }
        return targetRoles.stream().allMatch(CLUB_ADMIN_GRANTABLE::contains);
    }

    /** Returns the subset of {@code targetRoles} the caller is NOT permitted to grant. */
    public Set<Role> rejectedRoles(@org.jspecify.annotations.Nullable Jwt callerJwt, Collection<Role> targetRoles) {
        Set<Role> callerRoles = callerJwt == null ? Set.of() : readRealmRoles(callerJwt);
        if (callerRoles.contains(Role.CLUB_ADMINISTRATOR)
                && !callerRoles.contains(Role.SYSTEM_ADMINISTRATOR)) {
            return targetRoles.stream()
                    .filter(r -> !CLUB_ADMIN_GRANTABLE.contains(r))
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
