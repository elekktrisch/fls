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

    public JoinRequestResponse submit(Jwt jwt, String joinCode, @Nullable String note) {
        UUID sub = subjectOf(jwt);
        submitGuard.recordAndCheckRateLimit(sub);
        if (users.findAnyByKeycloakSub(sub).isPresent()) {
            throw new AlreadyClubMemberException();
        }
        UUID clubId = clubs.findActiveIdByJoinCode(joinCode.strip())
                .orElseThrow(UnknownJoinCodeException::new);
        submitGuard.checkDenyCooldown(sub, clubId);
        String email = emailOf(jwt);
        String friendlyName = friendlyNameOf(jwt);
        return withClubDisplay(
                Tenants.runAs(clubId, () -> writer.file(sub, email, friendlyName, clubId, note)));
    }

    public JoinRequestResponse withdraw(Jwt jwt, UUID requestId) {
        UUID sub = subjectOf(jwt);
        UUID clubId = tenantLookup.findClubIdById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        return withClubDisplay(
                Tenants.runAs(clubId, () -> writer.withdraw(requestId, sub)));
    }

    public Optional<JoinRequestResponse> latestForCaller(Jwt jwt) {
        UUID sub = subjectOf(jwt);
        return tenantLookup.findLatestClubIdBySub(sub)
                .flatMap(clubId -> Tenants.runAs(clubId,
                        () -> requests.findLatestBySub(sub).map(this::toResponseWithClub)));
    }

    @Transactional(readOnly = true)
    public List<PendingJoinRequestResponse> pendingForCurrentTenant() {
        return requests.findPendingForCurrentTenant().stream()
                .map(PendingJoinRequestResponse::from)
                .toList();
    }

    private JoinRequestResponse withClubDisplay(JoinRequest request) {
        UUID clubId = request.getClubId();
        Club club = Tenants.runAs(clubId, () -> clubs.findActiveById(clubId).orElse(null));
        return JoinRequestResponse.from(request, club);
    }

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
