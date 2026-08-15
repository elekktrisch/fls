package ch.alpenflight.deployments.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record DeploymentLifecycleTransitioned(UUID deploymentId,
                                               @Nullable LifecycleState fromState,
                                               LifecycleState toState,
                                               Instant occurredAt) {
}
