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
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort;
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

/**
 * Transactional service for the {@code User} aggregate.
 *
 * <p>Two write paths:
 *
 * <ol>
 *   <li><strong>Admin invite</strong> ({@link #invite}) — Keycloak-first,
 *       DB-second inside one Spring transaction. KC create on failure
 *       throws; DB insert on failure compensates with a KC {@code DELETE}
 *       (best-effort — failure logs at {@code USER_INVITE_KC_ORPHAN} at
 *       error level for operator reconciliation).</li>
 *   <li><strong>First-login JIT</strong> ({@link #materializeFromJwt}) —
 *       invoked from the {@code JitUserMaterializationFilter} via the
 *       {@code JitUserMaterializer} port. Runs in its own
 *       {@code REQUIRES_NEW} transaction so a materialise failure rolls
 *       back independently of the inbound request's tx. Idempotent via
 *       {@code findActiveByKeycloakSub} + {@code save}; the DB partial
 *       unique on {@code keycloak_sub} is the structural net for the
 *       dual-request race.</li>
 * </ol>
 *
 * <p>Role reads + writes go through {@link UserDirectoryPort}; the
 * application never imports the Keycloak adapter directly (ADR 0023).
 */
@Service
@Transactional
public class UsersService {

    private static final Logger LOG = LoggerFactory.getLogger(UsersService.class);
    private static final String AUDIT_USER = "User";
    private static final String AUDIT_USER_ROLE = "UserRole";
    private static final int LIST_MAX = 200;

    private final UserRepository users;
    private final UserDirectoryPort kc;
    private final RoleAssignmentPolicy rolePolicy;
    private final ClubTenantIdentifierResolver tenantResolver;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public UsersService(UserRepository users,
                        UserDirectoryPort kc,
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
        // One KC list call scoped to clubId gives enabled / requiredActions
        // for every row in a single round-trip. Role mappings still cost
        // one KC call per row (KC has no batch role-mapping endpoint);
        // accepted at per-club user counts ≤ a few hundred — revisit when
        // the perf plan promotes this endpoint to Page<>.
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

        UUID kcSub = kc.createUser(new UserDirectorySpec(
                req.username(),
                req.notificationEmail(),
                req.friendlyName(),
                /*lastName=*/ "",
                tenant,
                req.languageId().toString(),
                List.of("UPDATE_PASSWORD"),
                /*enabled=*/ true));

        // Re-entry path: if a soft-deleted tombstone is still holding
        // this KC sub, detach it so the partial UNIQUE on keycloak_sub
        // admits the new row. The tombstone keeps its audit history;
        // linkage between old + new is via username + KC user id, not a
        // FK chain.
        users.findAnyByKeycloakSub(kcSub).ifPresent(existing -> {
            if (!existing.isActive()) {
                existing.detachKeycloakSub();
                users.save(existing);
                users.flush();
            }
        });

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

        UUID rowId = requireId(saved);
        applyRoleDelta(rowId, kcSub, Set.of(), req.roles());
        try {
            kc.sendExecuteActions(kcSub, List.of("UPDATE_PASSWORD"), Duration.ofHours(12));
        } catch (UserDirectoryException ex) {
            // Don't roll back; the operator can re-send via resendInvite.
            LOG.warn("send invite email failed sub={} — operator can resend-invite", kcSub, ex);
        }
        UserResponse response = toResponse(saved);
        auditTrail.record(AuditAction.CREATE, AuditedTarget.created(AUDIT_USER, rowId, response));
        return response;
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

    public void softDelete(UserId id, @Nullable UUID callerUserId, @Nullable Jwt callerJwt) {
        User u = loadInCurrentTenantOrThrow(id);
        UUID rowId = requireId(u);
        if (callerUserId == null) {
            // Fail-closed: a caller with a valid CLUB_ADMINISTRATOR JWT but
            // no matching local row is an anomaly (the JIT projection should
            // have run); refuse the delete rather than admit one that
            // would skip the self-delete check.
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
        // Audit the operator-initiated re-send so a compromised CLUB_ADMIN
        // spamming password-reset emails is visible in the forensic trail.
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.created(AUDIT_USER_ROLE, requireId(u),
                        new UserRoleAuditPayload("RESEND_INVITE", "UPDATE_PASSWORD")));
    }

    /**
     * Materialise a local row from JWT claims when the lookup-by-sub misses.
     * Invoked from {@code JitUserMaterializationFilter} via the
     * {@code JitUserMaterializer} port.
     *
     * <p>{@code REQUIRES_NEW} so a materialise failure rolls back
     * independently of the inbound request's transaction and the
     * race-loser re-read runs in a fresh session after the winner's
     * commit becomes visible.
     *
     * <p>Identity fields ({@code keycloakSub}, {@code clubId}) are taken
     * authoritatively from the JWT — never from a caller-supplied value —
     * so a caller can't mint a JIT row in a tenant the principal doesn't
     * belong to. {@code languageId} comes from the caller because the JWT
     * carries {@code locale} as an opaque BCP-47 string that the
     * application has to map onto a row in the {@code language} table.
     *
     * @return the local {@code user.id} after materialise (or pre-existing match).
     */
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
                            finalEmail, languageId, /*personId=*/ null);
                    User saved = users.save(u);
                    users.flush();
                    UUID rowId = requireId(saved);
                    // Audit snapshot reads roles from the JWT's
                    // realm_access.roles claim — KC is the issuer, so the
                    // JWT carries the same set of roles `kc.getRealmRoleMappings`
                    // would return. Avoids one KC round-trip on the
                    // first-login hot path.
                    UserResponse snapshot = toJitResponse(saved, rolesFromJwt(jwt));
                    auditTrail.record(AuditAction.CREATE,
                            AuditedTarget.created(AUDIT_USER, rowId, snapshot));
                    return rowId;
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
                /*enabled=*/ u.isActive(),
                /*invitePending=*/ false);
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

    /**
     * Stable target-id for the rejection row so repeated rejections for the
     * same (club, username, attempted-role) correlate in the audit trail
     * without leaking the username verbatim. SHA-1 first 16 bytes folded
     * into a UUID — same pattern as PersonsService.hashLookupKey.
     */
    private void recordRoleGrantRejected(UUID clubId, String username, Set<Role> rejected) {
        for (Role r : rejected) {
            UUID stableTarget = stableRejectionId(clubId, username, r);
            auditTrail.recordFailed(AuditAction.UPDATE,
                    AuditedTarget.created(AUDIT_USER_ROLE, stableTarget,
                            new UserRoleAuditPayload("REJECT_GRANT", r.name())),
                    403, "USER_ROLE_GRANT_REJECTED");
        }
    }

    private static UUID stableRejectionId(UUID clubId, String username, Role role) {
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
        // Read CLUB_ADMINISTRATOR's role-membership in one KC call rather
        // than per-row. KC returns every user in the realm with the role;
        // we intersect with the club's active users in the loop.
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
                return false; // another live CLUB_ADMINISTRATOR exists in this club
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

    /**
     * Typed audit-event payload for the {@code UserRole} entity-type rows
     * (grant / revoke / reject). Fields match the {@code UserRole} block
     * in {@code application.yml}'s redaction allow-list — neither carries
     * PII, both ride verbatim into the audit trail.
     */
    private record UserRoleAuditPayload(String action, String targetRole) {}
}
