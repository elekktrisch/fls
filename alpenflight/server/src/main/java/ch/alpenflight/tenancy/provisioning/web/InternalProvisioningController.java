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
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-profile-only trigger surface for IT and Playwright e2e to exercise
 * the S-138 provisioning orchestration without dragging in the S-141
 * bundle-ingest pipeline. {@code @Profile("test")} keeps the bean out
 * of production contexts; the URL is scoped under {@code /internal/} so a
 * future production filter (gateway-level) can deny the prefix wholesale.
 *
 * <p>The request body carries the owner sub explicitly so the test can
 * provision under any synthesised principal. In production S-141 derives
 * the owner from the JWT principal it has already authorized against the
 * migration upload.
 */
@RestController
@RequestMapping("/api/v1/internal/migrations")
@Profile("test")
@Hidden
@AuditedBy("deploymentProvisioningService")
class InternalProvisioningController {

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
        // Phase B fires synchronously after Phase A commits so the test
        // observes the final state via this endpoint. Production wires
        // the reconcile call as part of S-141's per-bundle pipeline +
        // hourly retry job; the test endpoint does both inline.
        boolean kcPending = result.kcPending();
        try {
            provisioningService.reconcileKeycloak(result.deploymentId());
            kcPending = false;
        } catch (RuntimeException e) {
            // Phase B failure leaves the DB intact with kc_state=PENDING;
            // the test asserts on this outcome explicitly.
        }

        return ResponseEntity.ok(new ProvisioningResponse(
                result.deploymentId(),
                result.clubIds(),
                result.primaryClubId(),
                kcPending));
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
            boolean kcPending) {}
}
