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
