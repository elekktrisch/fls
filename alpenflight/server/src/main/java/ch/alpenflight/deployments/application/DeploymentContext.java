package ch.alpenflight.deployments.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DeploymentContext {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentContext.class);

    private final DeploymentRepository deployments;
    private final ClubRepository clubs;

    public DeploymentContext(DeploymentRepository deployments, ClubRepository clubs) {
        this.deployments = deployments;
        this.clubs = clubs;
    }

    public List<Deployment> findDeployment(LifecycleState... states) {
        if (states.length == 0) {
            return List.of();
        }
        return deployments.findByLifecycleStateIn(List.of(states));
    }

    public void forEachClub(UUID deploymentId, Consumer<Club> body) {
        List<UUID> clubIds = clubs.findIdsByDeploymentId(deploymentId);
        for (UUID clubId : clubIds) {
            Tenants.runAs(clubId, () ->
                    clubs.findActiveById(clubId).ifPresent(body));
        }
    }

    public <T> T foldOverClubs(String jobName,
                               T identity,
                               BiFunction<T, Club, T> accumulator,
                               LifecycleState... states) {
        Object[] total = {identity};
        for (Deployment deployment : findDeployment(states)) {
            UUID deploymentId = deployment.getId();
            if (deploymentId == null) {
                continue;
            }
            forEachClub(deploymentId, club -> {
                try {
                    total[0] = accumulator.apply(cast(total[0]), club);
                } catch (RuntimeException e) {
                    LOG.error("{} failed for club {} — continuing", jobName, club.getId(), e);
                }
            });
        }
        return cast(total[0]);
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value) {
        return (T) value;
    }
}
