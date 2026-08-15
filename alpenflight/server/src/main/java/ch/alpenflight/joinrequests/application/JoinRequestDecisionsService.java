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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public JoinRequestDecisionsService(JoinRequestRepository requests,
                                       UserRepository users,
                                       PersonRepository persons,
                                       UserDirectoryPort directory,
                                       RoleAssignmentPolicy rolePolicy,
                                       LanguageCodeLookup languages,
                                       AuditTrail auditTrail,
                                       ApplicationEventPublisher events,
                                       Clock clock) {
        this.requests = requests;
        this.users = users;
        this.persons = persons;
        this.directory = directory;
        this.rolePolicy = rolePolicy;
        this.languages = languages;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    public JoinRequestResponse approve(Jwt adminJwt, UUID requestId, ApproveJoinRequest cmd) {
        JoinRequest request = requests.findById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        UUID adminUserId = adminUserIdOrThrow(adminJwt);
        UUID sub = request.getKeycloakSub();
        UUID clubId = request.getClubId();

        request.approve(adminUserId, clock);

        Set<Role> roles = cmd.parsedRoles();
        if (!rolePolicy.isGrantable(adminJwt, roles)) {
            throw new ForbiddenRoleGrantException(rolePolicy.rejectedRoles(adminJwt, roles));
        }

        if (users.findAnyByKeycloakSub(sub).isPresent()) {
            throw new AlreadyClubMemberException();
        }

        directory.writeClubIdAttribute(sub, clubId);
        try {
            UUID personId = cmd.personId() == null
                    ? autoCreatePerson(clubId, request)
                    : linkExistingPerson(cmd.personId());
            User user = User.register(clubId, sub, usernameFromSignupEmail(request), request.getFriendlyName(),
                    request.getEmail(), languages.resolve(null), personId);
            User savedUser = users.save(user);
            users.flush();

            grantRoles(sub, roles);

            JoinRequest saved = requests.save(request);
            auditTrail.record(AuditAction.STATE_TRANSITION,
                    AuditedTarget.updated(AUDIT_ENTITY_TYPE, saved.getId(), saved, saved));
            UserId rowId = savedUser.getId();
            if (rowId != null) {
                auditTrail.record(AuditAction.CREATE,
                        AuditedTarget.created("User", rowId.value(), new UserCreatedAuditPayload(clubId, sub)));
            }
            events.publishEvent(JoinRequestStatusChangedEvent.from(saved));
            return JoinRequestResponse.from(saved);
        } catch (RuntimeException e) {
            try {
                directory.clearClubIdAttribute(sub);
            } catch (RuntimeException compensationFailure) {
                e.addSuppressed(compensationFailure);
            }
            throw e;
        }
    }

    public JoinRequestResponse deny(Jwt adminJwt, UUID requestId, DenyJoinRequest cmd) {
        JoinRequest request = requests.findById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        UUID adminUserId = adminUserIdOrThrow(adminJwt);
        request.deny(cmd.reason(), adminUserId, clock);
        JoinRequest saved = requests.save(request);
        auditTrail.record(AuditAction.STATE_TRANSITION,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, saved.getId(), saved, saved));
        events.publishEvent(JoinRequestStatusChangedEvent.from(saved));
        return JoinRequestResponse.from(saved);
    }

    private UUID autoCreatePerson(UUID clubId, JoinRequest request) {
        Person p = Person.register(firstNameOf(request), nonBlankLastNameOf(request), null);
        p.joinClub(clubId, null, null, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
        Person saved = persons.save(p);
        persons.flush();
        return idOf(saved);
    }

    private UUID linkExistingPerson(UUID personId) {
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
                .orElseThrow(() -> new IllegalStateException(
                        "Approving admin has no resolved user row — refusing decision"));
    }

    private static String usernameFromSignupEmail(JoinRequest request) {
        return request.getEmail();
    }

    private static String firstNameOf(JoinRequest request) {
        String name = request.getFriendlyName().strip();
        int space = name.indexOf(' ');
        return space > 0 ? name.substring(0, space) : name;
    }

    private static String nonBlankLastNameOf(JoinRequest request) {
        String name = request.getFriendlyName().strip();
        int space = name.indexOf(' ');
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

    private record UserCreatedAuditPayload(UUID clubId, @Nullable UUID keycloakSub) {}
}
