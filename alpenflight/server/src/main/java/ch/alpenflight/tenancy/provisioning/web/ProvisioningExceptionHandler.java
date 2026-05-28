package ch.alpenflight.tenancy.provisioning.web;

import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@link DeploymentExistsException} into the structured 409
 * response shape the SPA needs to surface the "go to your existing
 * tenant" CTA (S-138 refinement § Edge cases).
 *
 * <p>Scope-narrowed to the provisioning controller's package so a future
 * module raising the same exception type by mistake doesn't inherit this
 * mapping — module-local advice for module-local error vocabulary, per
 * the same pattern as the deployments exception handler (ADR 0023).
 */
@RestControllerAdvice(basePackageClasses = InternalProvisioningController.class)
class ProvisioningExceptionHandler {

    /**
     * The 409 body the SPA renders. {@code clubIds} is intentionally
     * surfaced — the SPA's CTA includes a deep link to the existing
     * Deployment's primary Club, picked deterministically as the lowest
     * UUID until S-141 surfaces a manifest-declared primary on the
     * Deployment row.
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
}
