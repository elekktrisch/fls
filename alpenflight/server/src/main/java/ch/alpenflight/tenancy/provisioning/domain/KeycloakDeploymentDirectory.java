package ch.alpenflight.tenancy.provisioning.domain;

import java.util.List;
import java.util.UUID;

public interface KeycloakDeploymentDirectory {

    UUID findOrCreateDeploymentGroup(UUID deploymentId);

    void addUserToGroupIfAbsent(UUID userKeycloakSub, UUID groupId);

    UUID findOrCreateClubAdminRole(UUID deploymentId, UUID clubId);

    void assignRoleIfAbsent(UUID userKeycloakSub, UUID roleId, String roleName);

    void setUserAttribute(UUID userKeycloakSub, String attributeName, List<String> values);

    // RENAME: provisionClubAdminIdentity -> provisionClubAdminIdentityFailClosed
    UUID provisionClubAdminIdentity(UUID clubId, String username, String email,
                                    String firstName, String lastName);
}
