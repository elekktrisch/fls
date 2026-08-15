package ch.alpenflight.tenancy.provisioning.web;

import ch.alpenflight.audit.domain.AuditedBy;
import ch.alpenflight.tenancy.provisioning.application.ClubSpec;
import ch.alpenflight.tenancy.provisioning.application.DeploymentProvisioningService;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningRequest;
import ch.alpenflight.tenancy.provisioning.application.ProvisioningResult;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/migrations")
@Profile("test")
@Hidden
@AuditedBy("lifecycleTransitionAuditListener")
class InternalProvisioningController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalProvisioningController.class);

    private final DeploymentProvisioningService provisioningService;

    InternalProvisioningController(DeploymentProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping("/{idempotencyKey}/provision")
    ResponseEntity<ProvisioningResponse> provision(
            @PathVariable UUID idempotencyKey,
            @RequestBody ProvisioningRequestBody body) {
        ProvisioningRequest request = new ProvisioningRequest(
                idempotencyKey,
                body.ownerKeycloakSub(),
                body.deploymentName(),
                body.clubs(),
                body.primaryClubId());

        ProvisioningResult result = provisioningService.provision(request);
        boolean keycloakPending = result.keycloakPending();
        try {
            provisioningService.reconcileKeycloak(result.deploymentId());
            keycloakPending = false;
        } catch (RuntimeException reconcileFailure) {
            LOG.warn("Keycloak reconcile failed for Deployment {} — kc_state stays PENDING",
                    result.deploymentId(), reconcileFailure);
        }

        return ResponseEntity.ok(new ProvisioningResponse(
                result.deploymentId(),
                result.clubIds(),
                result.primaryClubId(),
                keycloakPending));
    }

    public record ProvisioningRequestBody(
            UUID ownerKeycloakSub,
            String deploymentName,
            List<ClubSpec> clubs,
            @Nullable UUID primaryClubId) {}

    public record ProvisioningResponse(
            UUID deploymentId,
            List<UUID> clubIds,
            UUID primaryClubId,
            boolean keycloakPending) {}
}
