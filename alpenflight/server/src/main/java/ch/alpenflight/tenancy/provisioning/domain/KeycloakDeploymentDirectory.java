package ch.alpenflight.tenancy.provisioning.domain;

import java.util.List;
import java.util.UUID;

/**
 * Directory-side port for Deployment provisioning. Two contracts, by
 * caller:
 *
 * <ul>
 *   <li><b>Self-service signup (best-effort + reconcile).</b> The DB-half
 *       (Deployment + Clubs + reference-data) commits inside the signup
 *       transaction; the directory methods below ({@code findOrCreate*},
 *       {@code addUserToGroupIfAbsent}, {@code assignRoleIfAbsent},
 *       {@code setUserAttribute}) run post-commit, best-effort, and are
 *       retried by the hourly reconcile job from {@code kc_state=PENDING}
 *       when they fail mid-flight.</li>
 *   <li><b>Migration ingest (fail-closed).</b>
 *       {@link #provisionClubAdminIdentity} runs <em>inside</em> the ingest
 *       transaction and has NO reconcile-later fallback — a failure rolls
 *       the whole migrate back. See that method's javadoc.</li>
 * </ul>
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
 *
 * <p>Naming helpers for the group + role labels live on
 * {@link KeycloakDeploymentNames} so the application service, the
 * adapter, and the cleanup cascade share one source of truth.
 */
public interface KeycloakDeploymentDirectory {

    /**
     * Returns the directory id of the group named
     * {@link KeycloakDeploymentNames#deploymentGroupName(UUID)}, creating
     * it if absent.
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
     * {@link KeycloakDeploymentNames#clubAdminRoleName(UUID, UUID)},
     * creating it if absent. The verbose name is intentional — the
     * operator never types it, and the prefix lets the trial-delete
     * cascade find every role to clean up.
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
     * Sets the named user-attribute to the given value list. The caller
     * passes the primary Club's id under
     * {@link KeycloakDeploymentNames#CLUB_ID_USER_ATTRIBUTE} so the JWT
     * {@code clubId} claim resolves to the user's first tenant on token
     * refresh.
     *
     * <p>NOTE: today's adapter sends the directory's {@code PUT
     * /users/{id}} with a single-key {@code attributes} map, which the
     * directory treats as a full replace — fine because these freshly-
     * provisioned users carry no other attributes, but it would silently
     * drop sibling attributes if any were added later. Tighten to a
     * read-then-merge if the per-user attribute surface grows.
     */
    void setUserAttribute(UUID userKeycloakSub, String attributeName, List<String> values);

    /**
     * Provisions a fresh, loginable club-admin identity for a migrated
     * Club (J-0c, the thin provision-on-migrate slice of S-028). Unlike
     * the self-service-signup reconcile above — which binds the existing
     * Deployment owner to a per-Deployment dynamic role — this mints a
     * brand-new directory user carrying:
     * <ul>
     *   <li>the migrated Club's {@code clubId} user-attribute (so the
     *       realm's {@code clubId} mapper projects the claim
     *       {@code @TenantId} resolves off — the same mechanism J-0's
     *       {@code two-club-fixture} relies on);</li>
     *   <li>the realm role {@code CLUB_ADMINISTRATOR};</li>
     *   <li>{@code firstName} + {@code lastName} — the realm's declarative
     *       user-profile marks both {@code required: { roles: ["user"] }}
     *       (realm-export.json), so a {@code user}-roled account with a
     *       blank name triggers Keycloak's dynamically-triggered
     *       {@code VERIFY_PROFILE} required action on first login and never
     *       leaves {@code /realms/}. Setting both at mint time makes the
     *       migrated admin loginable in one shot (J-1 T-06 — replaces the
     *       removed e2e {@code makeMigratedAdminLoginable} name fixup);</li>
     *   <li>the {@code UPDATE_PASSWORD} required action — migrated legacy
     *       passwords never cross over (C14), so the operator sets one on
     *       first login.</li>
     * </ul>
     *
     * <p>Idempotent: a repeat call for a username/email that already
     * exists short-circuits to the existing directory sub rather than
     * surfacing the directory's 409.
     *
     * <p><b>Fail-closed on the migration path.</b> The migration-ingest
     * caller ({@code MigrationBundleIngestService}) invokes this
     * <em>inside</em> the ingest transaction, before any entity stream
     * drains — a thrown {@link ch.alpenflight.tenancy.provisioning.infra.KeycloakDeploymentDirectoryAdapter}
     * provisioning exception propagates out and rolls the whole ingest
     * back. There is no PENDING / reconcile-later fallback for this method:
     * either the migrated admin is loginable or the migrate fails (operator
     * intent — "Keycloak has to work", no shortcuts). The best-effort +
     * hourly-reconcile contract documented on this interface applies to the
     * <em>self-service-signup</em> methods above ({@code findOrCreate*},
     * {@code addUserToGroupIfAbsent}, {@code assignRoleIfAbsent},
     * {@code setUserAttribute}), which the reconcile job retries from
     * {@code kc_state=PENDING} — NOT to this migration-provisioning call.
     *
     * @param firstName non-blank given name set on the directory user so
     *     {@code VERIFY_PROFILE} does not fire (synthetic for the migrated
     *     admin — the identity is a per-Club service account, not a legacy
     *     Person row; see {@code DeploymentProvisioningService}).
     * @param lastName  non-blank family name, same rationale.
     * @return the directory-assigned {@code sub} of the (created or
     *     pre-existing) club-admin user.
     */
    UUID provisionClubAdminIdentity(UUID clubId, String username, String email,
                                    String firstName, String lastName);
}
