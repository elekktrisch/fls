package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component("aircraftAccess")
public class AircraftAccess {

    private static final String ROLE_SYSTEM_ADMIN = "ROLE_SYSTEM_ADMINISTRATOR";
    private static final String ROLE_CLUB_ADMIN = "ROLE_CLUB_ADMINISTRATOR";
    private static final String ROLE_FLIGHT_OPERATOR = "ROLE_FLIGHT_OPERATOR";

    private final AircraftRepository aircrafts;
    private final UserPrincipalLookup principals;

    public AircraftAccess(AircraftRepository aircrafts, UserPrincipalLookup principals) {
        this.aircrafts = aircrafts;
        this.principals = principals;
    }

    public boolean canRegister(@Nullable Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        return hasAnyRole(jwt, ROLE_CLUB_ADMIN);
    }

    public boolean canEdit(AircraftId id, @Nullable Jwt jwt) {
        return canMutate(id, jwt, false);
    }

    public boolean canOperate(AircraftId id, @Nullable Jwt jwt) {
        return canMutate(id, jwt, true);
    }

    public boolean canViewManagerOnlyData(@Nullable UUID managingClubId) {
        Jwt jwt = currentJwt();
        if (jwt == null) {
            return false;
        }
        if (hasAnyRole(jwt, ROLE_SYSTEM_ADMIN)) {
            return true;
        }
        if (managingClubId == null) {
            return false;
        }
        UUID callerClubId = resolveCallerClubId(jwt);
        return callerClubId != null && managingClubId.equals(callerClubId);
    }

    private static @Nullable Jwt currentJwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return jwtAuth.getToken();
        }
        return null;
    }

    private boolean canMutate(AircraftId id, @Nullable Jwt jwt, boolean allowFlightOperator) {
        if (jwt == null) {
            return false;
        }
        if (hasAnyRole(jwt, ROLE_SYSTEM_ADMIN)) {
            return true;
        }
        Aircraft aircraft = aircrafts.findActiveById(id.value()).orElse(null);
        if (aircraft == null) {
            return false;
        }
        if (isOwnerPerson(jwt, aircraft.getAircraftOwnerPersonId())) {
            return true;
        }
        UUID managingClubId = aircraft.getManagingClubId();
        UUID callerClubId = resolveCallerClubId(jwt);
        if (managingClubId == null || callerClubId == null
                || !managingClubId.equals(callerClubId)) {
            return false;
        }
        if (hasAnyRole(jwt, ROLE_CLUB_ADMIN)) {
            return true;
        }
        return allowFlightOperator && hasAnyRole(jwt, ROLE_FLIGHT_OPERATOR);
    }

    private boolean isOwnerPerson(Jwt jwt, @Nullable UUID ownerPersonId) {
        if (ownerPersonId == null) {
            return false;
        }
        UUID callerPersonId = principals.resolvePersonIdFor(jwt).orElse(null);
        return callerPersonId != null && callerPersonId.equals(ownerPersonId);
    }

    private @Nullable UUID resolveCallerClubId(Jwt jwt) {
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

    private static boolean hasAnyRole(Jwt jwt, String... roles) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (!(realmAccess instanceof java.util.Map<?, ?> ra)) {
            return false;
        }
        Object rolesClaim = ra.get("roles");
        if (!(rolesClaim instanceof Collection<?> coll)) {
            return false;
        }
        for (Object role : coll) {
            if (!(role instanceof String name)) {
                continue;
            }
            String prefixed = "ROLE_" + name;
            for (String wanted : roles) {
                if (wanted.equals(prefixed)) {
                    return true;
                }
            }
        }
        return false;
    }

}
