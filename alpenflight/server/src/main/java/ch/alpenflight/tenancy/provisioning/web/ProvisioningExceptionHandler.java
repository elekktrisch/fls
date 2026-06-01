package ch.alpenflight.tenancy.provisioning.web;

import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException;
import ch.alpenflight.tenancy.provisioning.domain.IdempotencyOwnerMismatchException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates provisioning-domain exceptions into HTTP responses:
 *
 * <ul>
 *   <li>{@link DeploymentExistsException} → 409 with a structured body
 *       the SPA reads to surface its "go to your existing tenant" CTA.</li>
 *   <li>{@link IdempotencyOwnerMismatchException} → 404 with empty body
 *       (no existence leak).</li>
 * </ul>
 *
 * <p>Scope-narrowed via {@code basePackageClasses} so a future module
 * raising the same exception type by mistake doesn't inherit this
 * mapping — module-local advice for module-local error vocabulary, per
 * ADR 0023.
 */
@RestControllerAdvice(basePackageClasses = InternalProvisioningController.class)
class ProvisioningExceptionHandler {

    /**
     * The 409 body the SPA renders. {@code clubIds} is intentionally
     * surfaced so the CTA can deep-link to the existing Deployment's
     * primary Club; the SPA resolves the primary identically to the
     * provisioning service.
     */
    public record DeploymentExistsBody(
            String code,
            UUID deploymentId,
            String deploymentName,
            LifecycleState lifecycleState,
            List<UUID> clubIds) {}

    @ExceptionHandler(DeploymentExistsException.class)
    ResponseEntity<DeploymentExistsBody> handleExists(DeploymentExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DeploymentExistsBody(
                        "DEPLOYMENT_EXISTS",
                        e.existingDeploymentId(),
                        e.existingDeploymentName(),
                        e.existingLifecycleState(),
                        e.existingClubIds()));
    }

    @ExceptionHandler(IdempotencyOwnerMismatchException.class)
    ResponseEntity<Void> handleOwnerMismatch(IdempotencyOwnerMismatchException e) {
        // 404 + empty body: shape is identical to "key never bound" so
        // a caller cannot distinguish between the two outcomes.
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
