package ch.alpenflight.tenancy.provisioning.application;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of {@link DeploymentProvisioningService#provision}.
 * {@code keycloakPending} is {@code true} when the directory-side
 * reconcile (group + per-Club admin roles + clubId user attribute)
 * couldn't complete on the synchronous attempt; the hourly reconcile
 * job re-invokes
 * {@link DeploymentProvisioningService#reconcileKeycloak} with this
 * Deployment's id until it succeeds.
 */
public record ProvisioningResult(
        UUID deploymentId,
        List<UUID> clubIds,
        UUID primaryClubId,
        boolean keycloakPending) {

    public ProvisioningResult {
        clubIds = List.copyOf(clubIds);
    }
}
