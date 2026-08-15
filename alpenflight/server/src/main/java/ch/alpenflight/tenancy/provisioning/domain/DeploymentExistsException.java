package ch.alpenflight.tenancy.provisioning.domain;

import ch.alpenflight.deployments.domain.LifecycleState;
import java.util.List;
import java.util.UUID;

public class DeploymentExistsException extends RuntimeException {

    private final UUID existingDeploymentId;
    private final String existingDeploymentName;
    private final LifecycleState existingLifecycleState;
    private final List<UUID> existingClubIds;

    public DeploymentExistsException(
            UUID existingDeploymentId,
            String existingDeploymentName,
            LifecycleState existingLifecycleState,
            List<UUID> existingClubIds) {
        super("owner already holds Deployment " + existingDeploymentId
                + " in state " + existingLifecycleState);
        this.existingDeploymentId = existingDeploymentId;
        this.existingDeploymentName = existingDeploymentName;
        this.existingLifecycleState = existingLifecycleState;
        this.existingClubIds = List.copyOf(existingClubIds);
    }

    public UUID existingDeploymentId() {
        return existingDeploymentId;
    }

    public String existingDeploymentName() {
        return existingDeploymentName;
    }

    public LifecycleState existingLifecycleState() {
        return existingLifecycleState;
    }

    public List<UUID> existingClubIds() {
        return existingClubIds;
    }
}
