package ch.alpenflight.deployments.application;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.deployments.domain.Plan;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class DeploymentDtos {

    private DeploymentDtos() {}

    public record LifecycleTransitionRequest(
            @NotNull(message = "targetState is required")
            LifecycleState targetState) {
    }

    public record DeploymentResponse(UUID id,
                                     LifecycleState lifecycleState,
                                     Plan plan,
                                     @Nullable Instant trialStartedAt) {
        public static DeploymentResponse from(Deployment deployment) {
            UUID id = deployment.getId();
            if (id == null) {
                throw new IllegalStateException("Deployment id is null on a saved aggregate");
            }
            return new DeploymentResponse(
                    id,
                    deployment.getLifecycleState(),
                    deployment.getPlan(),
                    deployment.getTrialStartedAt());
        }
    }
}
