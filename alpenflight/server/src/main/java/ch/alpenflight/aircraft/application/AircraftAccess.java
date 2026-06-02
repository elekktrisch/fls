package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * SpEL bean wired into {@code @PreAuthorize("@aircraftAccess.canEdit(...)")}
 * expressions on the Aircraft controller. Encapsulates the manager-club
 * gate for the cross-tenant Aircraft aggregate (S-058 reversion of S-159).
 *
 * <p>The predicate uses {@code managing_club_id} (the operational manager),
 * NOT {@code owner_club_id} (physical-owner metadata). This handles the
 * Club-A-operates-an-external-owner-aircraft case: managing_club_id =
 * Club A, owner_club_id = NULL, and Club A's admins still maintain the
 * record.
 *
 * <ul>
 *   <li>{@link #canEdit canEdit} — masterdata mutations (update,
 *       soft-delete, transfer-ownership). Requires CLUB_ADMINISTRATOR of
 *       {@code managing_club_id}, or SYSTEM_ADMINISTRATOR.</li>
 *   <li>{@link #canOperate canOperate} — operational mutations (state /
 *       counter). Same predicate as {@link #canEdit} but admits the
 *       FLIGHT_OPERATOR role within the managing club.</li>
 *   <li>{@link #canRegister canRegister} — pre-creation gate. The aircraft
 *       doesn't exist yet; plain role-check (CLUB_ADMINISTRATOR or
 *       SYSTEM_ADMINISTRATOR). Service layer sets managing_club_id to the
 *       caller's club.</li>
 * </ul>
 *
 * <p>The {@code aircraft_owner_person_id} field is intentionally NOT
 * consulted here. A future story layers in the person-owner predicate when
 * S-052 (Users CRUD) wires User→Person.
 */
@Component("aircraftAccess")
public class AircraftAccess {

    private static final String ROLE_SYSTEM_ADMIN = "ROLE_SYSTEM_ADMINISTRATOR";
    private static final String ROLE_CLUB_ADMIN = "ROLE_CLUB_ADMINISTRATOR";
    private static final String ROLE_FLIGHT_OPERATOR = "ROLE_FLIGHT_OPERATOR";

    private final AircraftRepository aircrafts;

    public AircraftAccess(AircraftRepository aircrafts) {
        this.aircrafts = aircrafts;
    }

    /**
     * Gate for {@code POST /api/v1/aircraft} — registration. The managing
     * club is read from the caller's tenant resolver at the service layer,
     * so only callers that carry a {@code clubId} claim may register.
     * That means CLUB_ADMINISTRATOR (with a clubId) — SYSTEM_ADMINISTRATOR
     * is explicitly NOT admitted here (sysadmin lacks the clubId claim and
     * the service would throw); a future story can add a dedicated
     * sysadmin variant with an explicit managingClubId field.
     */
    public boolean canRegister(@Nullable Jwt jwt) {
        if (jwt == null) {
            return false;
        }
        return hasAnyRole(jwt, ROLE_CLUB_ADMIN);
    }

    /**
     * Gate for masterdata mutations (update, soft-delete, transfer-ownership).
     * CLUB_ADMINISTRATOR of {@code managing_club_id}, or SYSTEM_ADMINISTRATOR.
     */
    public boolean canEdit(AircraftId id, @Nullable Jwt jwt) {
        return canMutate(id, jwt, false);
    }

    /**
     * Gate for operational mutations (state, counter). Same predicate as
     * {@link #canEdit} but FLIGHT_OPERATOR is also admitted within the
     * owning club.
     */
    public boolean canOperate(AircraftId id, @Nullable Jwt jwt) {
        return canMutate(id, jwt, true);
    }

    /**
     * Read-side gate for manager-only fields inlined on the cross-tenant
     * detail projection — currently {@code latestCounter} (S-164). Reads of
     * the row stay open (any authenticated user, S-058), but the managing
     * club's operational counter is redacted for everyone else. Uses the
     * same managing-club predicate as {@link #canEdit}: caller's
     * {@code clubId} == {@code managingClubId}, with SYSTEM_ADMINISTRATOR as
     * the universal fallback (consistent with {@link #canMutate}). The JWT is
     * read from the current {@link SecurityContextHolder} so the service can
     * apply the redaction without leaking the principal through the
     * controller signature.
     */
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
        UUID managingClubId = resolveManagingClubId(id);
        if (managingClubId == null) {
            // Missing / soft-deleted aircraft: deny non-sysadmin to keep the
            // 404-on-write IDOR contract — the SpEL gate returns false; the
            // service-layer load surfaces the 404 to the caller anyway.
            return false;
        }
        UUID callerClubId = resolveCallerClubId(jwt);
        if (callerClubId == null || !managingClubId.equals(callerClubId)) {
            return false;
        }
        if (hasAnyRole(jwt, ROLE_CLUB_ADMIN)) {
            return true;
        }
        return allowFlightOperator && hasAnyRole(jwt, ROLE_FLIGHT_OPERATOR);
    }

    private @Nullable UUID resolveManagingClubId(AircraftId id) {
        return aircrafts.findActiveById(id.value())
                .map(Aircraft::getManagingClubId)
                .orElse(null);
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
