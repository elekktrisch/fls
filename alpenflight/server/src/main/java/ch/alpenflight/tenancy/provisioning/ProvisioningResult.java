package ch.alpenflight.tenancy.provisioning;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of {@link DeploymentProvisioningService#provision}. {@code kcPending}
 * is {@code true} when Phase B (Keycloak group + role plumbing) couldn't
 * complete on the synchronous attempt; S-141's hourly reconcile job re-invokes
 * {@link DeploymentProvisioningService#reconcileKeycloak} with this Deployment's
 * id until it succeeds.
 */
public record ProvisioningResult(
        UUID deploymentId,
        List<UUID> clubIds,
        UUID primaryClubId,
        boolean kcPending) {

    public ProvisioningResult {
        clubIds = List.copyOf(clubIds);
    }
}
