package ch.alpenflight.tenancy.provisioning.domain;

import java.util.UUID;

/**
 * Single source of truth for the Keycloak group + realm-role naming
 * convention used by the deployment-directory port and its adapter. The
 * application service, the adapter, and the future deletion-cascade
 * worker all derive labels through this helper so a rename can never
 * drift one caller out of step with another.
 *
 * <p>The {@code deployment-} / {@code club-} / {@code -admin} infixes
 * are intentional anchors: scripts that scan the realm for orphaned
 * artifacts can prefix-match by either segment.
 */
public final class KeycloakDeploymentNames {

    private static final String DEPLOYMENT_PREFIX = "deployment-";
    private static final String CLUB_INFIX = "-club-";
    private static final String ADMIN_SUFFIX = "-admin";

    /** Attribute name on the Keycloak user whose value is the primary Club id. */
    public static final String CLUB_ID_USER_ATTRIBUTE = "clubId";

    /**
     * Realm role granting club-administrator authority. Static fixture in
     * the realm export ({@code auth/realm-export.json}), unlike the
     * per-Deployment dynamic roles built by {@link #clubAdminRoleName}.
     * The J-0c provision-on-migrate slice grants this realm role to the
     * synthetic migrated club admin.
     */
    public static final String CLUB_ADMINISTRATOR_REALM_ROLE = "CLUB_ADMINISTRATOR";

    private KeycloakDeploymentNames() {}

    /** Group label that carries Deployment membership. */
    public static String deploymentGroupName(UUID deploymentId) {
        return DEPLOYMENT_PREFIX + deploymentId;
    }

    /** Realm role label granting admin authority on one Club inside a Deployment. */
    public static String clubAdminRoleName(UUID deploymentId, UUID clubId) {
        return DEPLOYMENT_PREFIX + deploymentId + CLUB_INFIX + clubId + ADMIN_SUFFIX;
    }
}
