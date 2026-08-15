package ch.alpenflight.users.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.application.UserDtos.UserInviteRequest;
import ch.alpenflight.users.application.UserDtos.UserListItem;
import ch.alpenflight.users.application.UserDtos.UserResponse;
import ch.alpenflight.users.application.UserDtos.UserUpdateRequest;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserConflictException;
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.DirectoryUser;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import ch.alpenflight.users.domain.UserDirectoryPort.UserDirectoryRow;
import ch.alpenflight.users.domain.UserDirectoryPort.UserDirectorySpec;
import ch.alpenflight.users.domain.UserNotFoundException;
import ch.alpenflight.users.domain.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UsersService {

    private static final Logger LOG = LoggerFactory.getLogger(UsersService.class);
    private static final String AUDIT_USER = "User";
    private static final String AUDIT_USER_ROLE = "UserRole";
    private static final String AUDIT_INVITED = "InvitedAuditPayload";
    private static final int LIST_MAX = 200;
    private static final String BRANCH_NEW_KC_USER = "new_kc_user";
    private static final String BRANCH_ATTACHED_EXISTING = "attached_existing";
    private static final String WELCOME_ATTACHED_TEMPLATE = "users/welcome-attached";
    private static final String WELCOME_ATTACHED_SUBJECT = "Welcome to AlpenFlight";
    private static final String NO_LAST_NAME = "";
    private static final boolean KC_USER_ENABLED_ON_CREATE = true;
    private static final boolean INVITE_PENDING_NOT_PROJECTED = false;

    private final UserRepository users;
    private final UserDirectoryPort kc;
    private final RoleAssignmentPolicy rolePolicy;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final AuditTrail auditTrail;
    private final ClubRepository clubs;
    private final TemplatedMailService mail;
    private final Clock clock;

    public UsersService(UserRepository users,
                        UserDirectoryPort kc,
                        RoleAssignmentPolicy rolePolicy,
                        ClubTenantIdentifierResolver tenantResolver,
                        AuditTrail auditTrail,
                        ClubRepository clubs,
                        TemplatedMailService mail,
                        Clock clock) {
        this.users = users;
        this.kc = kc;
        this.rolePolicy = rolePolicy;
        this.tenantResolver = tenantResolver;
        this.auditTrail = auditTrail;
        this.clubs = clubs;
        this.mail = mail;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long countAllActiveUsers() {
        return users.countAllActive();
    }

    @Transactional(readOnly = true)
    public List<UserListItem> listInCurrentTenant() {
        UUID tenant = currentTenantOrThrow();
        List<UserRepository.ListRow> rows = users.findActiveInClub(tenant);
        Map<UUID, UserDirectoryRow> kcBySub = indexBySub(kc.findUsersInClub(tenant, LIST_MAX));
        if (kcBySub.size() >= LIST_MAX) {
            LOG.warn("Keycloak users-in-club list hit cap={} for club={} — list view may understate enabled/invitePending",
                    LIST_MAX, tenant);
        }
        return rows.stream().map(row -> {
            UserDirectoryRow kcRow = row.keycloakSub() == null ? null : kcBySub.get(row.keycloakSub());
            return new UserListItem(
                    UserId.of(row.id()),
                    row.username(),
                    row.friendlyName(),
                    row.notificationEmail(),
                    row.personId(),
                    rolesForKcSub(row.keycloakSub()),
                    kcRow != null && Boolean.TRUE.equals(kcRow.enabled()),
                    invitePending(kcRow));
        }).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(UserId id) {
        User u = loadInCurrentTenantOrThrow(id);
        return toResponse(u);
    }

    public UserResponse invite(UserInviteRequest req, @Nullable Jwt callerJwt) {
        UUID tenant = currentTenantOrThrow();
        if (!rolePolicy.isGrantable(callerJwt, req.roles())) {
            recordRoleGrantRejected(tenant, req.username(),
                    rolePolicy.rejectedRoles(callerJwt, req.roles()));
            throw new ForbiddenRoleGrantException(rolePolicy.rejectedRoles(callerJwt, req.roles()));
        }
        users.findActiveByUsernameLower(req.username()).ifPresent(existing -> {
            throw new UserConflictException("username " + req.username() + " already in use in club " + existing.getClubId());
        });

        String lowercasedEmailAsKeycloakStoresIt =
                req.notificationEmail().toLowerCase(java.util.Locale.ROOT);
        Optional<DirectoryUser> existingKc = kc.findUserByEmail(lowercasedEmailAsKeycloakStoresIt);
        if (existingKc.isPresent()) {
            DirectoryUser kcUser = existingKc.get();
            if (kcUser.clubId() != null) {
                LOG.warn("USER_INVITE_ATTACHED_ELSEWHERE email-sub={} attachedClub={} invitingClub={}",
                        kcUser.sub(), kcUser.clubId(), tenant);
                throw new UserConflictException(
                        "this email is already attached to another club — they need to leave "
                        + "that club first, OR you can share your join code instead.");
            }
            return bindExistingKcUser(tenant, req, kcUser);
        }

        return createNewKcUser(tenant, req);
    }

    private UserResponse createNewKcUser(UUID tenant, UserInviteRequest req) {
        UUID kcSub = kc.createUser(new UserDirectorySpec(
                req.username(),
                req.notificationEmail(),
                req.friendlyName(),
                NO_LAST_NAME,
                tenant,
                req.languageId().toString(),
                List.of("UPDATE_PASSWORD"),
                KC_USER_ENABLED_ON_CREATE));

        users.findAnyByKeycloakSub(kcSub).ifPresent(existing -> {
            if (!existing.isActive()) {
                existing.detachKeycloakSub();
                users.save(existing);
                users.flush();
            }
        });

        User saved;
        try {
            saved = registerUser(tenant, kcSub, req);
        } catch (RuntimeException e) {
            try {
                kc.deleteUser(kcSub);
            } catch (RuntimeException kcDeleteFailure) {
                LOG.error("USER_INVITE_KC_ORPHAN sub={} username={} (compensation failed)",
                        kcSub, req.username(), kcDeleteFailure);
            }
            throw e;
        }

        UUID rowId = requireId(saved);
        applyRoleDelta(rowId, kcSub, Set.of(), req.roles());
        try {
            kc.sendExecuteActions(kcSub, List.of("UPDATE_PASSWORD"), Duration.ofHours(12));
        } catch (UserDirectoryException ex) {
            LOG.warn("send invite email failed sub={} — operator can resend-invite", kcSub, ex);
        }
        UserResponse response = toResponse(saved);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_INVITED, rowId, new InvitedAuditPayload(BRANCH_NEW_KC_USER, response)));
        return response;
    }

    private UserResponse bindExistingKcUser(UUID tenant, UserInviteRequest req, DirectoryUser kcUser) {
        UUID kcSub = kcUser.sub();
        kc.writeClubIdAttribute(kcSub, tenant);
        User saved;
        try {
            saved = registerUser(tenant, kcSub, req);
            UUID rowId = requireId(saved);
            applyRoleDelta(rowId, kcSub, Set.of(), req.roles());
        } catch (RuntimeException e) {
            try {
                kc.clearClubIdAttribute(kcSub);
            } catch (RuntimeException compensationFailure) {
                e.addSuppressed(compensationFailure);
            }
            throw e;
        }

        sendWelcomeAttached(tenant, req.notificationEmail(), req.friendlyName(), kcUser.locale());
        UserResponse response = toResponse(saved);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_INVITED, requireId(saved),
                        new InvitedAuditPayload(BRANCH_ATTACHED_EXISTING, response)));
        return response;
    }

    private User registerUser(UUID tenant, UUID kcSub, UserInviteRequest req) {
        User u = User.register(tenant, kcSub, req.username(), req.friendlyName(),
                req.notificationEmail(), req.languageId(), req.personId());
        u.updateProfile(req.friendlyName(), req.notificationEmail(),
                req.phoneNumber(), req.remarks(), req.languageId());
        User saved = users.save(u);
        users.flush();
        return saved;
    }

    private void sendWelcomeAttached(UUID tenant, String email, String friendlyName, @Nullable String locale) {
        String clubName = clubs.findActiveById(tenant)
                .map(ch.alpenflight.clubs.domain.Club::getClubname)
                .orElse("");
        Map<String, Object> model = new HashMap<>();
        model.put("friendlyName", friendlyName);
        model.put("clubName", clubName);
        model.put("locale", normalizeLocale(locale));
        try {
            mail.send(email, WELCOME_ATTACHED_SUBJECT, WELCOME_ATTACHED_TEMPLATE, model);
        } catch (RuntimeException ex) {
            LOG.warn("welcome-attached email failed sub-email={} — bind committed", email, ex);
        }
    }

    private static String normalizeLocale(@Nullable String locale) {
        if (locale == null || locale.isBlank()) {
            return "en";
        }
        return locale.toLowerCase(java.util.Locale.ROOT).startsWith("de") ? "de" : "en";
    }

    public UserResponse update(UserId id, UserUpdateRequest req, @Nullable Jwt callerJwt) {
        User u = loadInCurrentTenantOrThrow(id);
        if (!rolePolicy.isGrantable(callerJwt, req.roles())) {
            recordRoleGrantRejected(u.getClubId(), u.getUsername(),
                    rolePolicy.rejectedRoles(callerJwt, req.roles()));
            throw new ForbiddenRoleGrantException(rolePolicy.rejectedRoles(callerJwt, req.roles()));
        }
        UserResponse beforeSnapshot = toResponse(u);
        u.updateProfile(req.friendlyName(), req.notificationEmail(),
                req.phoneNumber(), req.remarks(), req.languageId());
        if (req.personId() == null) {
            u.unlinkPerson();
        } else {
            u.assignToPerson(req.personId());
        }
        User saved = users.save(u);
        users.flush();
        UUID rowId = requireId(saved);
        UUID kcSub = saved.getKeycloakSub();
        if (kcSub != null) {
            Set<Role> existing = currentRoles(kcSub);
            applyRoleDelta(rowId, kcSub, existing, req.roles());
        }
        UserResponse after = toResponse(saved);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_USER, rowId, beforeSnapshot, after));
        return after;
    }

    public void updateOwnProfile(@Nullable Jwt callerJwt, SelfProfileUpdate cmd) {
        UUID sub = callerSubOrThrow(callerJwt);
        User u = users.findActiveByKeycloakSub(sub).orElseThrow(() ->
                new IllegalArgumentException("No active user row for the authenticated principal"));
        if (!users.languageExists(cmd.languageId())) {
            throw new IllegalArgumentException("Unknown languageId: " + cmd.languageId());
        }
        UserResponse before = selfEditSnapshotWithoutKeycloakRoleRead(u);
        u.updateProfile(cmd.friendlyName(), cmd.notificationEmail(),
                cmd.phoneNumber(), u.getRemarks(), cmd.languageId());
        User saved = users.save(u);
        users.flush();
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_USER, requireId(saved), before,
                        selfEditSnapshotWithoutKeycloakRoleRead(saved)));
    }

    private UserResponse selfEditSnapshotWithoutKeycloakRoleRead(User u) {
        return new UserResponse(
                UserId.of(requireId(u)),
                u.getClubId(),
                u.getUsername(),
                u.getFriendlyName(),
                u.getNotificationEmail(),
                u.getPhoneNumber(),
                u.getRemarks(),
                u.getLanguageId(),
                u.getPersonId(),
                List.of(),
                u.isActive(),
                INVITE_PENDING_NOT_PROJECTED);
    }

    private static UUID callerSubOrThrow(@Nullable Jwt jwt) {
        if (jwt == null) {
            throw new IllegalArgumentException("No authenticated principal");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("Authenticated principal has no subject");
        }
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Authenticated principal subject is not a UUID");
        }
    }

    public void softDelete(UserId id, @Nullable UUID callerUserId) {
        User u = loadInCurrentTenantOrThrow(id);
        UUID rowId = requireId(u);
        if (callerUserId == null) {
            throw new UserConflictException("Refusing delete — caller has no resolved user row");
        }
        if (callerUserId.equals(rowId)) {
            throw new UserConflictException("Refusing self-delete");
        }
        Set<Role> targetRoles = u.getKeycloakSub() == null ? Set.of() : currentRoles(u.getKeycloakSub());
        if (targetRoles.contains(Role.CLUB_ADMINISTRATOR)
                && isLastClubAdministrator(u.getClubId(), u.getKeycloakSub())) {
            throw new UserConflictException(
                    "Refusing to delete the last CLUB_ADMINISTRATOR of club " + u.getClubId());
        }
        UserResponse beforeSnapshot = toResponse(u);
        u.softDelete(callerUserId, clock);
        users.save(u);
        users.flush();
        if (u.getKeycloakSub() != null) {
            try {
                kc.setEnabled(u.getKeycloakSub(), false);
            } catch (UserDirectoryException ex) {
                LOG.warn("Keycloak disable failed sub={} — reconcile manually", u.getKeycloakSub(), ex);
            }
        }
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_USER, rowId, beforeSnapshot));
    }

    public void resendInvite(UserId id) {
        User u = loadInCurrentTenantOrThrow(id);
        UUID kcSub = u.getKeycloakSub();
        if (kcSub == null) {
            throw new UserConflictException("User " + id + " is not linked to a Keycloak identity");
        }
        kc.sendExecuteActions(kcSub, List.of("UPDATE_PASSWORD"), Duration.ofHours(12));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.created(AUDIT_USER_ROLE, requireId(u),
                        new UserRoleAuditPayload("RESEND_INVITE", "UPDATE_PASSWORD")));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID materializeFromJwt(Jwt jwt, UUID languageId) {
        if (jwt == null) {
            throw new IllegalArgumentException("jwt must not be null");
        }
        String subClaim = jwt.getSubject();
        if (subClaim == null || subClaim.isBlank()) {
            throw new IllegalArgumentException("jwt sub claim missing");
        }
        UUID keycloakSub;
        try {
            keycloakSub = UUID.fromString(subClaim);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("jwt sub claim is not a UUID", e);
        }
        String rawClubId = jwt.getClaimAsString("clubId");
        if (rawClubId == null || rawClubId.isBlank()) {
            throw new IllegalArgumentException("jwt clubId claim missing — refusing materialise");
        }
        UUID clubId;
        try {
            clubId = UUID.fromString(rawClubId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("jwt clubId claim is not a UUID", e);
        }
        String username = jwt.getClaimAsString("preferred_username");
        String friendlyName = jwt.getClaimAsString("given_name");
        String email = jwt.getClaimAsString("email");
        if (username == null || friendlyName == null || email == null) {
            throw new IllegalArgumentException(
                    "jwt missing one of preferred_username / given_name / email — refusing materialise");
        }
        final UUID finalClubId = clubId;
        final UUID finalSub = keycloakSub;
        final String finalUsername = username;
        final String finalFriendly = friendlyName;
        final String finalEmail = email;
        return users.findActiveByKeycloakSub(keycloakSub)
                .map(UsersService::requireId)
                .orElseGet(() -> {
                    User u = User.register(finalClubId, finalSub, finalUsername, finalFriendly,
                            finalEmail, languageId, null);
                    User saved = users.save(u);
                    users.flush();
                    UUID rowId = requireId(saved);
                    UserResponse snapshot = toJitResponse(saved, rolesFromJwt(jwt));
                    auditTrail.record(AuditAction.CREATE,
                            AuditedTarget.created(AUDIT_USER, rowId, snapshot));
                    return rowId;
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> reconcileSubByUsername(String username, UUID keycloakSub, UUID clubId) {
        return users.findActiveByUsernameLower(username)
                .flatMap(existing -> {
                    if (!Objects.equals(existing.getClubId(), clubId)) {
                        LOG.warn("JIT username-reconcile refused — username row club_id={} "
                                + "differs from JWT clubId={} (sub={}); leaving principal "
                                + "tenant-less (fail-closed)",
                                existing.getClubId(), clubId, keycloakSub);
                        return Optional.<UUID>empty();
                    }
                    existing.rebindKeycloakSub(keycloakSub);
                    users.save(existing);
                    users.flush();
                    UUID rowId = requireId(existing);
                    LOG.info("JIT reconciled keycloak_sub by username — userId={} clubId={} "
                            + "newSub={} (re-login under a fresh sub)", rowId, clubId, keycloakSub);
                    return Optional.of(rowId);
                });
    }

    private UserResponse toJitResponse(User u, List<Role> roles) {
        return new UserResponse(
                UserId.of(requireId(u)),
                u.getClubId(),
                u.getUsername(),
                u.getFriendlyName(),
                u.getNotificationEmail(),
                u.getPhoneNumber(),
                u.getRemarks(),
                u.getLanguageId(),
                u.getPersonId(),
                roles,
                u.isActive(),
                INVITE_PENDING_NOT_PROJECTED);
    }

    private static List<Role> rolesFromJwt(Jwt jwt) {
        Object claim = jwt.getClaim("realm_access");
        if (!(claim instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(Role::fromWire)
                .filter(Objects::nonNull)
                .toList();
    }

    private void applyRoleDelta(UUID localUserId, UUID sub, Set<Role> existing, Set<Role> desired) {
        Set<Role> toGrant = desired.stream()
                .filter(r -> !existing.contains(r))
                .collect(Collectors.toUnmodifiableSet());
        Set<Role> toRevoke = existing.stream()
                .filter(r -> !desired.contains(r))
                .collect(Collectors.toUnmodifiableSet());
        if (!toGrant.isEmpty()) {
            List<RealmRoleRef> grantRefs = kc.findRealmRolesByName(
                    toGrant.stream().map(Enum::name).collect(Collectors.toSet()));
            kc.grantRealmRoles(sub, grantRefs);
            for (Role r : toGrant) {
                auditTrail.record(AuditAction.UPDATE,
                        AuditedTarget.created(AUDIT_USER_ROLE, localUserId,
                                new UserRoleAuditPayload("GRANT", r.name())));
            }
        }
        if (!toRevoke.isEmpty()) {
            List<RealmRoleRef> revokeRefs = kc.findRealmRolesByName(
                    toRevoke.stream().map(Enum::name).collect(Collectors.toSet()));
            kc.revokeRealmRoles(sub, revokeRefs);
            for (Role r : toRevoke) {
                auditTrail.record(AuditAction.UPDATE,
                        AuditedTarget.created(AUDIT_USER_ROLE, localUserId,
                                new UserRoleAuditPayload("REVOKE", r.name())));
            }
        }
    }

    private void recordRoleGrantRejected(UUID clubId, String username, Set<Role> rejected) {
        for (Role r : rejected) {
            UUID stableTarget = stableHashedRejectionTargetId(clubId, username, r);
            auditTrail.recordFailed(AuditAction.UPDATE,
                    AuditedTarget.created(AUDIT_USER_ROLE, stableTarget,
                            new UserRoleAuditPayload("REJECT_GRANT", r.name())),
                    403, "USER_ROLE_GRANT_REJECTED");
        }
    }

    private static UUID stableHashedRejectionTargetId(UUID clubId, String username, Role role) {
        String canonical = clubId + "|" + username.toLowerCase(java.util.Locale.ROOT) + "|" + role.name();
        return UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Set<Role> currentRoles(UUID sub) {
        return kc.getRealmRoleMappings(sub).stream()
                .map(RealmRoleRef::name)
                .map(Role::fromWire)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private List<Role> rolesForKcSub(@Nullable UUID sub) {
        if (sub == null) {
            return List.of();
        }
        return currentRoles(sub).stream().toList();
    }

    private static Map<UUID, UserDirectoryRow> indexBySub(@Nullable List<UserDirectoryRow> rows) {
        if (rows == null) {
            return Map.of();
        }
        Map<UUID, UserDirectoryRow> map = new HashMap<>(rows.size());
        for (UserDirectoryRow row : rows) {
            map.put(row.id(), row);
        }
        return map;
    }

    private static boolean invitePending(@Nullable UserDirectoryRow row) {
        return row != null && row.requiredActions() != null && !row.requiredActions().isEmpty();
    }

    private boolean isLastClubAdministrator(UUID clubId, @Nullable UUID excludingSub) {
        if (excludingSub == null) {
            return false;
        }
        Set<UUID> adminSubs = kc.findUsersByRoleName(Role.CLUB_ADMINISTRATOR.name())
                .stream()
                .map(UserDirectoryRow::id)
                .collect(Collectors.toUnmodifiableSet());
        for (UserRepository.ListRow row : users.findActiveInClub(clubId)) {
            UUID rowSub = row.keycloakSub();
            if (rowSub == null || rowSub.equals(excludingSub)) {
                continue;
            }
            if (adminSubs.contains(rowSub)) {
                return false;
            }
        }
        return true;
    }

    private User loadInCurrentTenantOrThrow(UserId id) {
        User u = users.findActiveById(id.value())
                .orElseThrow(() -> new UserNotFoundException(id));
        UUID tenant = currentTenantOrThrow();
        if (!tenant.equals(u.getClubId())) {
            throw new UserNotFoundException(id);
        }
        return u;
    }

    private UUID currentTenantOrThrow() {
        UUID tenant = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
            throw new UserNotFoundException("No tenant context available — refusing tenant-scoped operation");
        }
        return tenant;
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(
                UserId.of(requireId(u)),
                u.getClubId(),
                u.getUsername(),
                u.getFriendlyName(),
                u.getNotificationEmail(),
                u.getPhoneNumber(),
                u.getRemarks(),
                u.getLanguageId(),
                u.getPersonId(),
                rolesForKcSub(u.getKeycloakSub()),
                u.isActive(),
                INVITE_PENDING_NOT_PROJECTED);
    }

    private static UUID requireId(User u) {
        UserId id = u.getId();
        if (id == null) {
            throw new IllegalStateException("User id is null after persist");
        }
        return id.value();
    }

    private record UserRoleAuditPayload(String action, String targetRole) {}

    private record InvitedAuditPayload(String branch, UserResponse user) {}
}
