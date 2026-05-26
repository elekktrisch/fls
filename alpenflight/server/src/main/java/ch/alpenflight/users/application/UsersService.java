package ch.alpenflight.users.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.id.UserId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.application.UserDtos.UserInviteRequest;
import ch.alpenflight.users.application.UserDtos.UserListItem;
import ch.alpenflight.users.application.UserDtos.UserResponse;
import ch.alpenflight.users.application.UserDtos.UserUpdateRequest;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserConflictException;
import ch.alpenflight.users.domain.UserNotFoundException;
import ch.alpenflight.users.domain.UserRepository;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient.KcRealmRoleRef;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient.KcUserRow;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient.KcUserSpec;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional service for the {@code User} aggregate.
 *
 * <p>The two write paths the S-052 design notes describe:
 *
 * <ol>
 *   <li><strong>Admin invite</strong> ({@link #invite}) — KC-first, DB-second
 *       inside one Spring transaction. KC create on failure throws; DB
 *       insert on failure compensates with a KC {@code DELETE} (best-effort
 *       — failure logs at {@code USER_INVITE_KC_ORPHAN} for operator
 *       reconciliation).</li>
 *   <li><strong>First-login JIT</strong> ({@link #materializeFromJwt}) —
 *       called by {@code UserPrincipalLookup} when the JWT carries
 *       {@code clubId} + non-empty realm roles but no row matches
 *       {@code sub}. Idempotent via {@code findActiveByKeycloakSub} +
 *       {@code save} (the DB partial unique on {@code keycloak_sub}
 *       provides the structural safety net).</li>
 * </ol>
 *
 * <p>Role reads + writes go through the {@link KeycloakAdminClient} façade;
 * the application never touches Keycloak data outside that boundary.
 */
@Service
@Transactional
public class UsersService {

    private static final Logger LOG = LoggerFactory.getLogger(UsersService.class);
    private static final String AUDIT_USER = "User";
    private static final int LIST_MAX = 200;

    private final UserRepository users;
    private final KeycloakAdminClient kc;
    private final RoleAssignmentPolicy rolePolicy;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public UsersService(UserRepository users,
                        KeycloakAdminClient kc,
                        RoleAssignmentPolicy rolePolicy,
                        ClubTenantIdentifierResolver tenantResolver,
                        AuditTrail auditTrail,
                        Clock clock) {
        this.users = users;
        this.kc = kc;
        this.rolePolicy = rolePolicy;
        this.tenantResolver = tenantResolver;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserListItem> listInCurrentTenant() {
        UUID tenant = currentTenantOrThrow();
        List<UserRepository.ListRow> rows = users.findActiveInClub(tenant);
        // One KC list call scoped to clubId — gives us roles[] / enabled /
        // requiredActions in one round-trip, avoiding an N+1 per row.
        Map<UUID, KcUserRow> kcBySub = indexBySub(kc.findUsersInClub(tenant, LIST_MAX));
        return rows.stream().map(row -> {
            User entity = users.findActiveById(row.id())
                    .orElseThrow(() -> new IllegalStateException("listed user vanished mid-read: " + row.id()));
            KcUserRow kcRow = entity.getKeycloakSub() == null ? null : kcBySub.get(entity.getKeycloakSub());
            return new UserListItem(
                    UserId.of(row.id()),
                    row.username(),
                    row.friendlyName(),
                    row.notificationEmail(),
                    row.personId(),
                    rolesForKcSub(entity.getKeycloakSub()),
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
            auditTrail.recordFailed(AuditAction.CREATE,
                    AuditedTarget.created(AUDIT_USER, UUID.randomUUID(),
                            new InvitePayloadStub(req.username(), req.roles())),
                    403, "USER_ROLE_GRANT_REJECTED");
            throw new ForbiddenRoleGrantException(rolePolicy.rejectedRoles(callerJwt, req.roles()));
        }
        users.findActiveByUsernameLower(req.username()).ifPresent(existing -> {
            throw new UserConflictException("username " + req.username() + " already in use in club " + existing.getClubId());
        });

        UUID kcSub = kc.createUser(new KcUserSpec(
                req.username(),
                req.notificationEmail(),
                req.friendlyName(),
                /*lastName=*/ "",
                tenant,
                req.languageId().toString(),
                List.of("UPDATE_PASSWORD"),
                /*enabled=*/ true));

        User saved;
        try {
            User u = User.register(tenant, kcSub, req.username(), req.friendlyName(),
                    req.notificationEmail(), req.languageId(), req.personId());
            u.updateProfile(req.friendlyName(), req.notificationEmail(),
                    req.phoneNumber(), req.remarks(), req.languageId());
            saved = users.save(u);
            users.flush();
        } catch (RuntimeException e) {
            // Compensating delete — best-effort. The audit row is critical
            // so the operator can reconcile if both KC and the compensation
            // fail.
            try {
                kc.deleteUser(kcSub);
            } catch (RuntimeException kcDeleteFailure) {
                LOG.error("USER_INVITE_KC_ORPHAN sub={} username={} (compensation failed)",
                        kcSub, req.username(), kcDeleteFailure);
            }
            throw e;
        }

        applyRoleDelta(kcSub, Set.of(), req.roles());
        try {
            kc.sendExecuteActions(kcSub, List.of("UPDATE_PASSWORD"), Duration.ofHours(12));
        } catch (KeycloakAdminException ex) {
            // Don't roll back; the operator can re-send via resendInvite.
            LOG.warn("send invite email failed sub={} — operator can resend-invite", kcSub, ex);
        }
        UUID rowId = requireId(saved);
        UserResponse response = toResponse(saved);
        auditTrail.record(AuditAction.CREATE, AuditedTarget.created(AUDIT_USER, rowId, response));
        return response;
    }

    public UserResponse update(UserId id, UserUpdateRequest req, @Nullable Jwt callerJwt) {
        User u = loadInCurrentTenantOrThrow(id);
        if (!rolePolicy.isGrantable(callerJwt, req.roles())) {
            auditTrail.recordFailed(AuditAction.UPDATE,
                    AuditedTarget.created(AUDIT_USER, requireId(u),
                            new InvitePayloadStub(u.getUsername(), req.roles())),
                    403, "USER_ROLE_GRANT_REJECTED");
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
        UUID kcSub = saved.getKeycloakSub();
        if (kcSub != null) {
            Set<Role> existing = currentRoles(kcSub);
            applyRoleDelta(kcSub, existing, req.roles());
        }
        UserResponse after = toResponse(saved);
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_USER, requireId(saved), beforeSnapshot, after));
        return after;
    }

    public void softDelete(UserId id, @Nullable UUID callerUserId, @Nullable Jwt callerJwt) {
        User u = loadInCurrentTenantOrThrow(id);
        UUID rowId = requireId(u);
        if (callerUserId != null && callerUserId.equals(rowId)) {
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
            } catch (KeycloakAdminException ex) {
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
    }

    /**
     * Materialise a local row from JWT claims when the lookup-by-sub misses.
     * Called by {@code UserPrincipalLookup} on every authenticated request
     * (cheap when a row already exists — the read returns immediately) so
     * the bulk-provision / federated-IdP flows converge on first hit.
     *
     * @return the local {@code user.id} after materialise (or pre-existing match).
     */
    public UUID materializeFromJwt(UUID keycloakSub, UUID clubId, String username,
                                   String friendlyName, String notificationEmail, UUID languageId) {
        return users.findActiveByKeycloakSub(keycloakSub)
                .map(UsersService::requireId)
                .orElseGet(() -> {
                    User u = User.register(clubId, keycloakSub, username, friendlyName,
                            notificationEmail, languageId, /*personId=*/ null);
                    User saved = users.save(u);
                    users.flush();
                    UUID rowId = requireId(saved);
                    auditTrail.record(AuditAction.CREATE,
                            AuditedTarget.created(AUDIT_USER, rowId, toResponse(saved)));
                    return rowId;
                });
    }

    private void applyRoleDelta(UUID sub, Set<Role> existing, Set<Role> desired) {
        Set<Role> toGrant = desired.stream()
                .filter(r -> !existing.contains(r))
                .collect(Collectors.toUnmodifiableSet());
        Set<Role> toRevoke = existing.stream()
                .filter(r -> !desired.contains(r) && Role.isKnown(r.name()))
                .collect(Collectors.toUnmodifiableSet());
        if (!toGrant.isEmpty()) {
            List<KcRealmRoleRef> grantRefs = kc.findRealmRolesByName(
                    toGrant.stream().map(Enum::name).collect(Collectors.toSet()));
            kc.grantRealmRoles(sub, grantRefs);
            for (Role r : toGrant) {
                auditTrail.record(AuditAction.UPDATE,
                        AuditedTarget.created("UserRole", sub,
                                Map.of("action", "GRANT", "targetRole", r.name())));
            }
        }
        if (!toRevoke.isEmpty()) {
            List<KcRealmRoleRef> revokeRefs = kc.findRealmRolesByName(
                    toRevoke.stream().map(Enum::name).collect(Collectors.toSet()));
            kc.revokeRealmRoles(sub, revokeRefs);
            for (Role r : toRevoke) {
                auditTrail.record(AuditAction.UPDATE,
                        AuditedTarget.created("UserRole", sub,
                                Map.of("action", "REVOKE", "targetRole", r.name())));
            }
        }
    }

    private Set<Role> currentRoles(UUID sub) {
        return kc.getRealmRoleMappings(sub).stream()
                .map(KcRealmRoleRef::name)
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

    private static Map<UUID, KcUserRow> indexBySub(@Nullable List<KcUserRow> rows) {
        if (rows == null) {
            return Map.of();
        }
        Map<UUID, KcUserRow> map = new HashMap<>(rows.size());
        for (KcUserRow row : rows) {
            map.put(row.id(), row);
        }
        return map;
    }

    private static boolean invitePending(@Nullable KcUserRow row) {
        return row != null && row.requiredActions() != null && !row.requiredActions().isEmpty();
    }

    private boolean isLastClubAdministrator(UUID clubId, @Nullable UUID excludingSub) {
        if (excludingSub == null) {
            return false;
        }
        long admins = users.findActiveInClub(clubId).stream()
                .filter(row -> {
                    UUID rowKcSub = users.findActiveById(row.id())
                            .map(User::getKeycloakSub)
                            .orElse(null);
                    if (rowKcSub == null || rowKcSub.equals(excludingSub)) {
                        return false;
                    }
                    return currentRoles(rowKcSub).contains(Role.CLUB_ADMINISTRATOR);
                }).count();
        return admins == 0;
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
                /*enabled=*/ u.isActive(),
                /*invitePending=*/ false);
    }

    private static UUID requireId(User u) {
        UserId id = u.getId();
        if (id == null) {
            throw new IllegalStateException("User id is null after persist");
        }
        return id.value();
    }

    /** Audit-payload stub for the role-grant rejection path — no PII. */
    private record InvitePayloadStub(String username, Set<Role> requestedRoles) {}
}
