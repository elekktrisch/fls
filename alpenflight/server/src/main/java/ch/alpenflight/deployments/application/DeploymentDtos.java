package ch.alpenflight.deployments.application;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.deployments.domain.Plan;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTOs for the deployments admin surface. Carries strictly the
 * fields the security plan permits in audit + telemetry: never the
 * {@code name} (operator free-text), never the billing IDs (PCI scope
 * adjacency), never the owner Keycloak sub on the read response.
 */
public final class DeploymentDtos {

    private DeploymentDtos() {}

    /** Request body for the admin lifecycle-flip endpoint. */
    public record LifecycleTransitionRequest(
            @NotNull(message = "targetState is required")
            LifecycleState targetState) {
    }

    /**
     * Response surface for the admin lifecycle-flip + read endpoints.
     * The deployment id, lifecycle state, plan, and trial timestamp are
     * the only fields the SPA / operator UI consumes today (S-144's
     * upgrade banner reads them). Audit + telemetry consume the same
     * shape via the redacted snapshot.
     */
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
