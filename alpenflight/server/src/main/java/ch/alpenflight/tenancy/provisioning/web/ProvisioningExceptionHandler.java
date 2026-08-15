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

@RestControllerAdvice(basePackageClasses = InternalProvisioningController.class)
class ProvisioningExceptionHandler {

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
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
