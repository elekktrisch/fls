package ch.alpenflight.tenancy.provisioning.application;

import java.util.List;
import java.util.UUID;

public record ProvisioningResult(
        UUID deploymentId,
        List<UUID> clubIds,
        UUID primaryClubId,
        boolean keycloakPending) {

    public ProvisioningResult {
        clubIds = List.copyOf(clubIds);
    }
}
