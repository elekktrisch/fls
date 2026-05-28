package ch.alpenflight.tenancy.provisioning.domain;

import java.util.List;
import java.util.UUID;

/**
 * Phase B reconcile port for the S-138 trial-Deployment provisioning flow.
 * The DB-half (Deployment + Clubs + reference-data) commits inside the
 * ingest transaction; this port runs post-commit, best-effort, and is
 * retried by the S-141 hourly reconcile job when it fails mid-flight.
 *
 * <p>Implementations MUST be idempotent — every method is "create-if-absent
 * + state-if-not-already" so a retry from any failure point is safe:
 *
 * <ul>
 *   <li>{@link #findOrCreateDeploymentGroup} creates the group named
 *       {@code deployment-{deploymentId}} if it doesn't exist, otherwise
 *       returns the existing group id.</li>
 *   <li>{@link #addUserToGroupIfAbsent} adds the user to the group if not
 *       already a member; a second call is a no-op.</li>
 *   <li>{@link #findOrCreateClubAdminRole} creates the realm role
 *       {@code deployment-{deploymentId}-club-{clubId}-admin} if absent.</li>
 *   <li>{@link #assignRoleIfAbsent} grants the role to the user if not
 *       already granted.</li>
 *   <li>{@link #setUserAttribute} replaces the named attribute's value list
 *       outright — idempotent because the post-condition matches the
 *       request regardless of the prior value.</li>
 * </ul>
 *
 * <p>The implementation lives under
 * {@code ch.alpenflight.tenancy.provisioning.infra} and talks to the
 * realm via the {@code alpenflight-backend-admin} machine client. The
 * client carries {@code manage-groups} + {@code manage-realm} +
 * {@code manage-users} so these operations succeed; the realm-shape
 * check script {@code alpenflight/auth/scripts/check-realm-shape.sh}
 * fails CI if the scope set drifts.
 */
public interface KeycloakDeploymentDirectory {

    /**
     * Returns the directory id of the group named
     * {@code deployment-{deploymentId}}, creating it if absent.
     */
    UUID findOrCreateDeploymentGroup(UUID deploymentId);

    /**
     * Idempotent group membership — adds the user to the group only if
     * not already a member. A second call short-circuits without an
     * upstream write.
     */
    void addUserToGroupIfAbsent(UUID userKeycloakSub, UUID groupId);

    /**
     * Returns the directory id of the realm role named
     * {@code deployment-{deploymentId}-club-{clubId}-admin}, creating it
     * if absent. The verbose name is intentional — the operator never
     * types it, and the prefix lets the trial-delete cascade (S-142)
     * find every role to clean up.
     */
    UUID findOrCreateClubAdminRole(UUID deploymentId, UUID clubId);

    /**
     * Idempotent realm-role assignment — grants the role to the user
     * only if the user does not already carry it. The directory's grant
     * API is array-shaped; the implementation pre-checks via the read
     * side so a retry doesn't surface as "already granted" or a duplicate
     * audit event.
     */
    void assignRoleIfAbsent(UUID userKeycloakSub, UUID roleId, String roleName);

    /**
     * Replaces the named user-attribute's value list with the given
     * list. The S-138 caller passes {@code clubId} as the attribute name
     * with a single-entry list whose value is the primary Club's id, so
     * the JWT {@code clubId} claim resolves to the user's first tenant
     * on token refresh.
     */
    void setUserAttribute(UUID userKeycloakSub, String attributeName, List<String> values);
}
