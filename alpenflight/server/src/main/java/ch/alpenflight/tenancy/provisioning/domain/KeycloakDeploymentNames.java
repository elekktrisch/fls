package ch.alpenflight.tenancy.provisioning.domain;

import java.util.UUID;

public final class KeycloakDeploymentNames {

    private static final String DEPLOYMENT_PREFIX = "deployment-";
    private static final String CLUB_INFIX = "-club-";
    private static final String ADMIN_SUFFIX = "-admin";

    // ext: Keycloak user-attribute name; realm-export.json maps it to the clubId claim
    public static final String CLUB_ID_USER_ATTRIBUTE = "clubId";

    // ext: realm role fixture in realm-export.json, asserted by check-realm-shape.sh
    public static final String CLUB_ADMINISTRATOR_REALM_ROLE = "CLUB_ADMINISTRATOR";

    private KeycloakDeploymentNames() {}

    public static String deploymentGroupName(UUID deploymentId) {
        return DEPLOYMENT_PREFIX + deploymentId;
    }

    public static String clubAdminRoleName(UUID deploymentId, UUID clubId) {
        return DEPLOYMENT_PREFIX + deploymentId + CLUB_INFIX + clubId + ADMIN_SUFFIX;
    }
}
