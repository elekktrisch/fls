package ch.alpenflight.deployments.application;

import ch.alpenflight.deployments.application.DeploymentDtos.DeploymentResponse;
import ch.alpenflight.deployments.application.DeploymentDtos.LifecycleTransitionRequest;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentNotFoundException;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.IllegalLifecycleTransitionException;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the {@code POST /api/v1/admin/deployments/{id}/lifecycle}
 * surface. Wraps {@link Deployment#transitionByAdmin} with the
 * cross-Deployment authz seam, the 404-on-unknown-id contract (per the
 * security plan: don't leak existence to token-leak probes), and the
 * sandbox-short-circuit guard.
 *
 * <p>The 409 path is owned by the controller-advice handler — the
 * service throws {@link IllegalLifecycleTransitionException} and the
 * web layer translates.
 */
@Service
@Transactional
public class DeploymentsAdminService {

    private final DeploymentRepository deployments;
    private final Clock clock;

    public DeploymentsAdminService(DeploymentRepository deployments, Clock clock) {
        this.deployments = deployments;
        this.clock = clock;
    }

    public DeploymentResponse transitionLifecycle(UUID deploymentId,
                                                  LifecycleTransitionRequest request) {
        Deployment deployment = deployments.findById(deploymentId)
                .orElseThrow(() -> new DeploymentNotFoundException(deploymentId));

        // Sandbox short-circuit before invoking the aggregate. Per the
        // security plan: defense-in-depth — the aggregate's transitionTo
        // also asserts, but a deliberate 409 from the surface keeps the
        // admin-audit-trail narration tight.
        if (deployment.getLifecycleState() == LifecycleState.SANDBOX) {
            throw new IllegalLifecycleTransitionException(
                    LifecycleState.SANDBOX,
                    request.targetState(),
                    "sandbox_immutable");
        }

        deployment.transitionByAdmin(request.targetState(), clock);
        Deployment saved = deployments.save(deployment);
        return DeploymentResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public DeploymentResponse getDeployment(UUID deploymentId) {
        return DeploymentResponse.from(deployments.findById(deploymentId)
                .orElseThrow(() -> new DeploymentNotFoundException(deploymentId)));
    }
}
