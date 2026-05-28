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

/**
 * Test-profile-only trigger surface for IT and Playwright e2e to exercise
 * the provisioning orchestration without dragging in the bundle-ingest
 * pipeline. {@code @Profile("test")} keeps the bean out of production
 * contexts; {@code @Hidden} keeps the route out of the OpenAPI snapshot.
 * The URL is scoped under {@code /internal/} so a future production
 * filter (gateway-level) can deny the prefix wholesale.
 *
 * <p>The request body carries the owner sub explicitly so the test can
 * provision under any synthesised principal. In production the ingest
 * pipeline derives the owner from the JWT principal it has already
 * authorized against the migration upload.
 */
@RestController
@RequestMapping("/api/v1/internal/migrations")
@Profile("test")
@Hidden
// Audit emission rides through LifecycleTransitionAuditListener
// (subscribes to the DeploymentLifecycleTransitioned event the
// provisioning service publishes via Spring Data on save).
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
        // Reconcile fires synchronously after the provisioning commit so
        // the test observes the final state via this endpoint. Production
        // wires the reconcile call as part of the bundle-ingest pipeline
        // + the hourly reconcile job; the test endpoint does both inline.
        boolean keycloakPending = result.keycloakPending();
        try {
            provisioningService.reconcileKeycloak(result.deploymentId());
            keycloakPending = false;
        } catch (RuntimeException reconcileFailure) {
            // Reconcile failure leaves the DB intact with kc_state=PENDING;
            // the test asserts on this outcome explicitly. Log so an
            // unexpected regression isn't silently swallowed.
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
