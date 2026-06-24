package ch.alpenflight.joinrequests.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.JoinRequestResponse;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.PendingJoinRequestResponse;
import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import ch.alpenflight.joinrequests.domain.JoinRequestTenantLookup;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.users.domain.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the join-request submit/read slice (S-178, T-05).
 *
 * <h2>The no-tenant-at-submit window</h2>
 *
 * <p>A pilot files a request BEFORE they belong to any club: the JWT carries a
 * Keycloak identity but no {@code clubId} claim and no {@code t_user} row, so
 * the tenant resolver yields {@code NO_TENANT}. {@code JoinRequest} is
 * {@code @TenantId}-scoped on {@code club_id}, so every persist/read against it
 * must run under a real tenant. This service resolves the target club FIRST —
 * from the join code at submit ({@link ClubRepository}), or from the pilot's
 * existing request via {@link JoinRequestTenantLookup} for me-read / withdraw —
 * then performs the JPA work inside {@link Tenants#runAs}. The identity fields
 * the aggregate stamps ({@code keycloak_sub} / {@code email} / {@code
 * friendlyName}) come straight off the JWT.
 */
@Service
public class JoinRequestsService {

    private final JoinRequestRepository requests;
    private final JoinRequestTenantLookup tenantLookup;
    private final JoinRequestTxWriter writer;
    private final ClubRepository clubs;
    private final UserRepository users;
    private final JoinRequestSubmitGuard submitGuard;

    public JoinRequestsService(JoinRequestRepository requests,
                               JoinRequestTenantLookup tenantLookup,
                               JoinRequestTxWriter writer,
                               ClubRepository clubs,
                               UserRepository users,
                               JoinRequestSubmitGuard submitGuard) {
        this.requests = requests;
        this.tenantLookup = tenantLookup;
        this.writer = writer;
        this.clubs = clubs;
        this.users = users;
        this.submitGuard = submitGuard;
    }

    /**
     * Files a pending request for the authenticated pilot against the club the
     * join code resolves to.
     *
     * @throws SubmitThrottledException 429 — rate limit or deny cooldown breached
     * @throws UnknownJoinCodeException 404 — no active club for the code
     * @throws AlreadyClubMemberException 409 — caller already has a {@code t_user}
     */
    public JoinRequestResponse submit(Jwt jwt, String joinCode, @Nullable String note) {
        UUID sub = subjectOf(jwt);
        // Brute-force window first: every attempt counts, even an unknown-code
        // probe, so the rate limit must record before the code resolves.
        submitGuard.recordAndCheckRateLimit(sub);
        if (users.findAnyByKeycloakSub(sub).isPresent()) {
            throw new AlreadyClubMemberException();
        }
        UUID clubId = clubs.findActiveIdByJoinCode(joinCode.strip())
                .orElseThrow(UnknownJoinCodeException::new);
        submitGuard.checkDenyCooldown(sub, clubId);
        String email = emailOf(jwt);
        String friendlyName = friendlyNameOf(jwt);
        // The tenant carrier must span the whole transaction (open → flush →
        // commit), so the write runs through the transactional writer INSIDE
        // runAs — see JoinRequestTxWriter.
        return withClubDisplay(
                Tenants.runAs(clubId, () -> writer.file(sub, email, friendlyName, clubId, note)));
    }

    /**
     * Withdraws the caller's own pending request (pending → withdrawn).
     *
     * @throws JoinRequestNotFoundException 404 — no request with that id
     * @throws NotJoinRequestOwnerException 403 — the request's sub is not the caller's
     */
    public JoinRequestResponse withdraw(Jwt jwt, UUID requestId) {
        UUID sub = subjectOf(jwt);
        UUID clubId = tenantLookup.findClubIdById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        return withClubDisplay(
                Tenants.runAs(clubId, () -> writer.withdraw(requestId, sub)));
    }

    /**
     * The caller's most recent request, or empty (→ 204) when they've filed
     * none. NOT {@code @Transactional}: Hibernate binds a session's tenant at
     * session-open, so a method-level transaction would fix the session to
     * {@code NO_TENANT} (the carrier isn't set yet) and the tenant-scoped read
     * inside {@link Tenants#runAs} would miss. Letting the JPA read open its own
     * session lazily inside {@code runAs} resolves the correct tenant.
     */
    public Optional<JoinRequestResponse> latestForCaller(Jwt jwt) {
        UUID sub = subjectOf(jwt);
        return tenantLookup.findLatestClubIdBySub(sub)
                .flatMap(clubId -> Tenants.runAs(clubId,
                        () -> requests.findLatestBySub(sub).map(this::toResponseWithClub)));
    }

    /** The caller's tenant's pending requests — CLUB_ADMINISTRATOR own-club list. */
    @Transactional(readOnly = true)
    public List<PendingJoinRequestResponse> pendingForCurrentTenant() {
        return requests.findPendingForCurrentTenant().stream()
                .map(PendingJoinRequestResponse::from)
                .toList();
    }

    /**
     * Folds the requested club's public-display fields (name / city / logo) into
     * the pilot's own response (S-178). The aggregate carries only {@code clubId}
     * and the pilot has no tenant, so the {@code /join/pending} screen needs the
     * club resolved here rather than via a cross-tenant endpoint. The club read
     * runs under the request's own tenant; an absent club leaves the fields null.
     */
    private JoinRequestResponse withClubDisplay(JoinRequest request) {
        UUID clubId = request.getClubId();
        Club club = Tenants.runAs(clubId, () -> clubs.findActiveById(clubId).orElse(null));
        return JoinRequestResponse.from(request, club);
    }

    /** As {@link #withClubDisplay} but for use already inside a tenant scope. */
    private JoinRequestResponse toResponseWithClub(JoinRequest request) {
        Club club = clubs.findActiveById(request.getClubId()).orElse(null);
        return JoinRequestResponse.from(request, club);
    }

    private static UUID subjectOf(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("JWT carries no subject");
        }
        return UUID.fromString(sub);
    }

    private static String emailOf(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new MissingPrincipalIdentityException("email");
        }
        return email;
    }

    /**
     * The pilot's display name from the JWT: {@code given_name family_name},
     * falling back to {@code preferred_username} then {@code email}. A signed-up
     * Keycloak user always carries at least one; an empty result is a
     * malformed token (400).
     */
    private static String friendlyNameOf(Jwt jwt) {
        String given = jwt.getClaimAsString("given_name");
        String family = jwt.getClaimAsString("family_name");
        String composed = (blankToEmpty(given) + " " + blankToEmpty(family)).strip();
        if (!composed.isBlank()) {
            return composed;
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username.strip();
        }
        return emailOf(jwt);
    }

    private static String blankToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
