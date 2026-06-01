package ch.alpenflight.deployments.web;

import ch.alpenflight.audit.domain.AuditedBy;
import ch.alpenflight.deployments.application.DeploymentDtos.DeploymentResponse;
import ch.alpenflight.deployments.application.DeploymentDtos.LifecycleTransitionRequest;
import ch.alpenflight.deployments.application.DeploymentsAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System-administrator-only Deployment lifecycle management.
 *
 * <ul>
 *   <li>{@code GET /} surfaces a single Deployment by id.</li>
 *   <li>{@code POST /{id}/lifecycle} flips lifecycle state.</li>
 * </ul>
 *
 * <p>{@link AuditedBy} on the controller class is the
 * {@code ControllerAuditCoverageTest} declarative-escape — the audit
 * row is emitted via the
 * {@link ch.alpenflight.deployments.application.LifecycleTransitionAuditListener}
 * domain-event subscriber, which ArchUnit's bounded DFS doesn't
 * traverse (Spring's event publisher is the hand-off).
 *
 * <p>Cross-Deployment surface, no tenant gate. 404 on unknown id (per
 * the security plan: don't leak existence to token-leak probes).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/deployments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Admin Deployments",
        description = "System-administrator Deployment lifecycle management.")
@AuditedBy("lifecycleTransitionAuditListener")
class DeploymentsAdminController {

    private final DeploymentsAdminService service;

    DeploymentsAdminController(DeploymentsAdminService service) {
        this.service = service;
    }

    @Operation(summary = "Read a Deployment by id.")
    @ApiResponse(responseCode = "200", description = "Deployment projection.")
    @ApiResponse(responseCode = "404", description = "No Deployment with that id.")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    DeploymentResponse getDeployment(@PathVariable UUID id) {
        return service.getDeployment(id);
    }

    @Operation(summary = "Flip a Deployment's lifecycle state.",
            description = "Admin-only surface for operator-driven transitions: ACTIVE for "
                    + "operator-owned tenants, CANCELLED for dunning-grace rescue, etc. "
                    + "Sandbox is immutable.")
    @ApiResponse(responseCode = "200", description = "Updated Deployment projection.")
    @ApiResponse(responseCode = "403", description = "Caller is not SYSTEM_ADMINISTRATOR.")
    @ApiResponse(responseCode = "404", description = "No Deployment with that id.")
    @ApiResponse(responseCode = "409", description = "Illegal transition (sandbox / no edge).")
    @PostMapping(path = "/{id}/lifecycle", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    DeploymentResponse transitionLifecycle(@PathVariable UUID id,
                                           @Valid @RequestBody LifecycleTransitionRequest request) {
        return service.transitionLifecycle(id, request);
    }
}
