package ch.alpenflight.tenancy.provisioning.application;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link DeploymentProvisioningService#provision}. The
 * {@code idempotencyKey} is a durable cross-attempt identifier bound by
 * the caller (e.g. the upstream migration-run id); a retry of the same
 * attempt short-circuits to the existing Deployment.
 *
 * <p>{@code primaryClubId} is the manifest-declared primary Club.
 * {@code null} triggers the deterministic fallback in
 * {@link DeploymentProvisioningService}.
 */
public record ProvisioningRequest(
        UUID idempotencyKey,
        UUID ownerKeycloakSub,
        String deploymentName,
        List<ClubSpec> clubs,
        @Nullable UUID primaryClubId) {

    public ProvisioningRequest {
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey must not be null");
        }
        if (ownerKeycloakSub == null) {
            throw new IllegalArgumentException("ownerKeycloakSub must not be null");
        }
        if (deploymentName == null || deploymentName.isBlank()) {
            throw new IllegalArgumentException("deploymentName must not be blank");
        }
        if (clubs == null || clubs.isEmpty()) {
            throw new IllegalArgumentException("clubs must not be empty");
        }
        clubs = List.copyOf(clubs);
    }
}
