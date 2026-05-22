package ch.alpenflight.aircraft.application;

import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.ClubId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SpEL access bean used by {@code @PreAuthorize} on the Aircraft endpoints.
 *
 * <p>Aircraft is cross-tenant (no {@code @TenantId}) — Hibernate does not
 * filter rows by club. The mutation authz contract lives here:
 *
 * <ul>
 *   <li>SYSTEM_ADMINISTRATOR may mutate any aircraft.</li>
 *   <li>CLUB_ADMINISTRATOR may mutate aircraft whose
 *       {@code owner_club_id} equals the JWT's {@code clubId} claim.</li>
 *   <li>{@code owner_club_id IS NULL} (charter / loan / private) is
 *       SYSTEM_ADMINISTRATOR-only — no club has structural standing.</li>
 * </ul>
 *
 * <p>Read is unscoped (any authenticated principal); the controller
 * gates that via {@code isAuthenticated()}, no call into this bean.
 */
@Component
public class AircraftAccess {

    private final AircraftRepository aircrafts;

    public AircraftAccess(AircraftRepository aircrafts) {
        this.aircrafts = aircrafts;
    }

    /**
     * True iff the current principal may mutate the given existing aircraft.
     * Called by {@code @PreAuthorize} on PUT / DELETE / state-change /
     * counter-record. Looks up the aircraft to read {@code owner_club_id};
     * a missing / soft-deleted aircraft returns {@code true} here so the
     * service-layer 404 takes precedence over a 403 (consistent error
     * shape for non-existent resources).
     */
    @Transactional(readOnly = true)
    public boolean canMutateAircraft(Authentication authentication, AircraftId id) {
        if (authentication == null || id == null) {
            return false;
        }
        if (hasRole(authentication, "SYSTEM_ADMINISTRATOR")) {
            return true;
        }
        Aircraft a = aircrafts.findActiveById(id.value()).orElse(null);
        if (a == null) {
            return true; // let 404 surface
        }
        UUID ownerClubId = a.getOwnerClubId();
        if (ownerClubId == null) {
            return false; // null-owner = SYSTEM_ADMIN-only
        }
        return hasRole(authentication, "CLUB_ADMINISTRATOR")
                && ownerClubId.equals(principalClubId(authentication));
    }

    /**
     * True iff the current principal may register a new aircraft with the
     * given (nullable) {@code ownerClubId} payload value.
     */
    public boolean canRegisterAircraft(Authentication authentication, @Nullable ClubId ownerClubId) {
        if (authentication == null) {
            return false;
        }
        if (hasRole(authentication, "SYSTEM_ADMINISTRATOR")) {
            return true;
        }
        if (ownerClubId == null) {
            return false; // null-owner = SYSTEM_ADMIN-only
        }
        return hasRole(authentication, "CLUB_ADMINISTRATOR")
                && ownerClubId.value().equals(principalClubId(authentication));
    }

    /**
     * True iff the current principal may change the airworthiness state of
     * the aircraft. FLIGHT_OPS / CLUB_ADMIN of {@code owner_club_id} may
     * operate; SYSTEM_ADMIN may operate regardless; null-owner falls to
     * SYSTEM_ADMIN-only. Airworthiness ties to the owning club's maintenance
     * regime, so cross-club state-change is denied.
     */
    @Transactional(readOnly = true)
    public boolean canOperateAircraft(Authentication authentication, AircraftId id) {
        if (authentication == null || id == null) {
            return false;
        }
        if (hasRole(authentication, "SYSTEM_ADMINISTRATOR")) {
            return true;
        }
        Aircraft a = aircrafts.findActiveById(id.value()).orElse(null);
        if (a == null) {
            return true; // 404 over 403
        }
        UUID ownerClubId = a.getOwnerClubId();
        if (ownerClubId == null) {
            return false;
        }
        if (!ownerClubId.equals(principalClubId(authentication))) {
            return false;
        }
        return hasRole(authentication, "FLIGHT_OPS")
                || hasRole(authentication, "CLUB_ADMINISTRATOR");
    }

    /**
     * True iff the current principal may record an operating-counter snapshot
     * against any aircraft. Counter totals are airframe-lifetime and
     * accumulate across operating clubs (a tow pilot at club B can record
     * hours flown for a charter aircraft owned by club A), so the role gate
     * is "FLIGHT_OPS / CLUB_ADMIN / SYSTEM_ADMIN" with no owner-club match.
     * Charter (null-owner) aircraft fall under the same rule.
     */
    public boolean canRecordCounter(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        return hasRole(authentication, "SYSTEM_ADMINISTRATOR")
                || hasRole(authentication, "CLUB_ADMINISTRATOR")
                || hasRole(authentication, "FLIGHT_OPS");
    }

    /**
     * True iff the current principal may see owner-only fields ({@code comment},
     * {@code flarmId}, {@code mtom}, {@code noiseClass}, {@code noiseLevel},
     * {@code spotLink}) on the {@code GET /aircraft/{id}} detail payload.
     * SYSTEM_ADMIN always; club members of the owning club; null-owner aircraft
     * are SYSTEM_ADMIN-only for the sensitive fields (charter PII is opaque to
     * any operating club).
     */
    public boolean canSeeOwnerOnlyFields(@Nullable Authentication authentication,
                                         @Nullable UUID ownerClubId) {
        if (authentication == null) {
            return false;
        }
        if (hasRole(authentication, "SYSTEM_ADMINISTRATOR")) {
            return true;
        }
        if (ownerClubId == null) {
            return false;
        }
        return ownerClubId.equals(principalClubId(authentication));
    }

    private static boolean hasRole(Authentication authentication, String role) {
        String wanted = "ROLE_" + role;
        for (GrantedAuthority ga : authentication.getAuthorities()) {
            if (wanted.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable UUID principalClubId(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            return null;
        }
        String claim = jwt.getClaimAsString("clubId");
        if (claim == null) {
            return null;
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
