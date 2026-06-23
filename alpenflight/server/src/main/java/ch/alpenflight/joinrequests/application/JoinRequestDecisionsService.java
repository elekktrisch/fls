package ch.alpenflight.joinrequests.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.ApproveJoinRequest;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.DenyJoinRequest;
import ch.alpenflight.joinrequests.application.JoinRequestDtos.JoinRequestResponse;
import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.tenancy.LanguageCodeLookup;
import ch.alpenflight.users.application.ForbiddenRoleGrantException;
import ch.alpenflight.users.application.RoleAssignmentPolicy;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import ch.alpenflight.users.domain.UserRepository;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approve / deny orchestration for the join-request slice (S-178, T-06) — the
 * cross-system seam. A CLUB_ADMINISTRATOR decides one of their own tenant's
 * pending requests; the controller's {@code @PreAuthorize} provides the role
 * gate and the admin's {@code clubId} claim resolves the tenant, so the
 * {@code @TenantId}-scoped {@link JoinRequest} load by id is automatically
 * filtered to the admin's club (a cross-tenant request loads as absent → 404).
 *
 * <h2>Cross-system ordering — the half-join defence</h2>
 *
 * <p>Approve writes to TWO systems: the Keycloak directory (the {@code clubId}
 * user-attribute, so the now-member's next JWT carries the claim) and the local
 * DB ({@code t_user} + Person/PersonClub + the aggregate transition). A partial
 * failure must not strand a half-joined user. The order is:
 *
 * <ol>
 *   <li>load + {@link JoinRequest#approve} in-memory — a non-pending request
 *       (already approved/denied/withdrawn) throws here, BEFORE any external
 *       write, so a re-approve is a 409 no-op;</li>
 *   <li>validate the {@code roles} are grantable (403 escalation guard);</li>
 *   <li>reject a sub that already has a {@code t_user} (409 — the one-sub-one-
 *       club rule makes a retry safe);</li>
 *   <li>write the KC {@code clubId} attribute — the FIRST external side effect,
 *       chosen first because it is idempotent (same value = no-op): if the DB
 *       transaction later rolls back, the stranded attribute is inert (a
 *       {@code clubId} attribute with no {@code t_user} grants nothing) and the
 *       request stays PENDING, so a re-approve re-runs the whole flow safely;</li>
 *   <li>create the {@code t_user}, link or auto-create Person + PersonClub, and
 *       grant the roles in KC (idempotent grant) — all inside the one
 *       transaction;</li>
 *   <li>commit the aggregate transition + audit.</li>
 * </ol>
 *
 * <p>Any failure after the KC attribute write rolls the DB transaction back and
 * leaves nothing load-bearing in either system — the integration-risk path the
 * IT pins.
 *
 * <p>Left for later tasks: the brute-force / 24h-deny cooldown guard (T-07) and
 * the success/deny email + the {@code join-request.status-changed} SSE publish
 * (T-08) — the {@code decided}/{@code denied} transitions here are the seam those
 * hook onto.
 */
@Service
@Transactional
public class JoinRequestDecisionsService {

    private static final String AUDIT_ENTITY_TYPE = "JoinRequest";

    private final JoinRequestRepository requests;
    private final UserRepository users;
    private final PersonRepository persons;
    private final UserDirectoryPort directory;
    private final RoleAssignmentPolicy rolePolicy;
    private final LanguageCodeLookup languages;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public JoinRequestDecisionsService(JoinRequestRepository requests,
                                       UserRepository users,
                                       PersonRepository persons,
                                       UserDirectoryPort directory,
                                       RoleAssignmentPolicy rolePolicy,
                                       LanguageCodeLookup languages,
                                       AuditTrail auditTrail,
                                       Clock clock) {
        this.requests = requests;
        this.users = users;
        this.persons = persons;
        this.directory = directory;
        this.rolePolicy = rolePolicy;
        this.languages = languages;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /**
     * Approves a pending request: KC attribute + {@code t_user} + Person/
     * PersonClub + roles + transition + audit, in the order documented on the
     * class.
     *
     * @throws JoinRequestNotFoundException 404 — no pending request in the admin's tenant
     * @throws ch.alpenflight.joinrequests.domain.IllegalJoinRequestStateException 409 — not pending (re-approve)
     * @throws AlreadyClubMemberException 409 — the sub already has a {@code t_user}
     * @throws CrossTenantPersonLinkException 409 — the picked Person is not in the admin's club
     * @throws ForbiddenRoleGrantException 403 — a role the admin may not grant
     */
    public JoinRequestResponse approve(Jwt adminJwt, UUID requestId, ApproveJoinRequest cmd) {
        JoinRequest request = requests.findById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        UUID adminUserId = adminUserIdOrThrow(adminJwt);
        UUID sub = request.getKeycloakSub();
        UUID clubId = request.getClubId();

        // (1) Validate the transition first — a non-pending request throws here,
        // before any external write, so re-approve is a no-op 409.
        request.approve(adminUserId, clock);

        // (2) Privilege-escalation gate.
        Set<Role> roles = cmd.parsedRoles();
        if (!rolePolicy.isGrantable(adminJwt, roles)) {
            throw new ForbiddenRoleGrantException(rolePolicy.rejectedRoles(adminJwt, roles));
        }

        // (3) One-sub-one-club: a sub that already has a t_user must not double-join.
        if (users.findAnyByKeycloakSub(sub).isPresent()) {
            throw new AlreadyClubMemberException();
        }

        // (4) KC clubId attribute — the idempotent first external side effect.
        directory.writeClubIdAttribute(sub, clubId);

        // (5) Local rows: t_user bound to the existing KC sub + a Person link.
        UUID personId = cmd.personId() == null
                ? autoCreatePerson(clubId, request)
                : linkExistingPerson(cmd.personId());
        User user = User.register(clubId, sub, friendlyToUsername(request), request.getFriendlyName(),
                request.getEmail(), languages.resolve(null), personId);
        User savedUser = users.save(user);
        users.flush();

        // (6) Roles in KC — idempotent grant.
        grantRoles(sub, roles);

        // (7) Commit the transition + audit.
        JoinRequest saved = requests.save(request);
        auditTrail.record(AuditAction.STATE_TRANSITION,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, saved.getId(), saved, saved));
        UserId rowId = savedUser.getId();
        if (rowId != null) {
            auditTrail.record(AuditAction.CREATE,
                    AuditedTarget.created("User", rowId.value(), new UserCreatedAuditPayload(clubId, sub)));
        }
        return JoinRequestResponse.from(saved);
    }

    /**
     * Denies a pending request (PENDING → DENIED) with an optional reason. No
     * cross-system writes — the cooldown the denial starts is enforced in T-07.
     *
     * @throws JoinRequestNotFoundException 404 — no pending request in the admin's tenant
     * @throws ch.alpenflight.joinrequests.domain.IllegalJoinRequestStateException 409 — not pending
     */
    public JoinRequestResponse deny(Jwt adminJwt, UUID requestId, DenyJoinRequest cmd) {
        JoinRequest request = requests.findById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        UUID adminUserId = adminUserIdOrThrow(adminJwt);
        request.deny(cmd.reason(), adminUserId, clock);
        JoinRequest saved = requests.save(request);
        auditTrail.record(AuditAction.STATE_TRANSITION,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, saved.getId(), saved, saved));
        return JoinRequestResponse.from(saved);
    }

    private UUID autoCreatePerson(UUID clubId, JoinRequest request) {
        Person p = Person.register(firstNameOf(request), lastNameOf(request), null);
        p.joinClub(clubId, null, null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
        Person saved = persons.save(p);
        persons.flush();
        return idOf(saved);
    }

    private UUID linkExistingPerson(UUID personId) {
        // The Person load is cross-tenant (Person has no @TenantId), so the
        // existence check is the tenant gate: hasActiveMembershipInCurrentTenant
        // is scoped to the admin's club, so a Person in another club fails it.
        if (!persons.hasActiveMembershipInCurrentTenant(personId)) {
            throw new CrossTenantPersonLinkException(personId);
        }
        return personId;
    }

    private void grantRoles(UUID sub, Set<Role> roles) {
        if (roles.isEmpty()) {
            return;
        }
        java.util.List<RealmRoleRef> refs = directory.findRealmRolesByName(
                roles.stream().map(Enum::name).collect(Collectors.toSet()));
        directory.grantRealmRoles(sub, refs);
    }

    private UUID adminUserIdOrThrow(Jwt adminJwt) {
        UUID sub = subjectOf(adminJwt);
        return users.findActiveByKeycloakSub(sub)
                .map(User::getId)
                .filter(java.util.Objects::nonNull)
                .map(UserId::value)
                // Fail-closed: a CLUB_ADMINISTRATOR JWT with no local t_user row
                // is an anomaly (the JIT projection should have run) — refuse the
                // decision rather than record a decided_by of null.
                .orElseThrow(() -> new IllegalStateException(
                        "Approving admin has no resolved user row — refusing decision"));
    }

    private static String friendlyToUsername(JoinRequest request) {
        // The KC username for the new member mirrors their email (the signup
        // identity); the t_user.username column is informational here — the JWT
        // sub is the join key.
        return request.getEmail();
    }

    private static String firstNameOf(JoinRequest request) {
        String name = request.getFriendlyName().strip();
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    private static String lastNameOf(JoinRequest request) {
        String name = request.getFriendlyName().strip();
        int space = name.indexOf(' ');
        // Person.register requires a non-blank lastname; a single-token name
        // falls back to the whole name so the auto-created Person is valid.
        return space > 0 && space + 1 < name.length() ? name.substring(space + 1).strip() : name;
    }

    private static UUID subjectOf(Jwt jwt) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("JWT carries no subject");
        }
        return UUID.fromString(sub);
    }

    private static UUID idOf(Person p) {
        var id = p.getId();
        if (id == null) {
            throw new IllegalStateException("Person id is null after persist");
        }
        return id.value();
    }

    /** Non-PII audit payload for the auto-created {@code t_user} — club + sub only. */
    private record UserCreatedAuditPayload(UUID clubId, @Nullable UUID keycloakSub) {}
}
