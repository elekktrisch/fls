package ch.alpenflight.tenancy.provisioning;

import ch.alpenflight.deployments.domain.LifecycleState;
import java.util.List;
import java.util.UUID;

/**
 * Owner already holds a non-terminal Deployment from a different ingest
 * attempt. Translates to a structured 409 response so the SPA can surface
 * a "go to your existing tenant" CTA. Schema-level partial UNIQUE
 * {@code ux_deployment_owner_active} (S-137) is the source of truth; this
 * exception carries the existing Deployment's identifiers for the response.
 */
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
