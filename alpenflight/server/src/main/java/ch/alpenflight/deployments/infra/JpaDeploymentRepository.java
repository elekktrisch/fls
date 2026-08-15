package ch.alpenflight.deployments.infra;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaDeploymentRepository
        extends JpaRepository<Deployment, UUID>, DeploymentRepository {

    @Override
    @Query("select d from Deployment d where d.lifecycleState in :states order by d.id")
    List<Deployment> findByLifecycleStateIn(List<LifecycleState> states);

    @Override
    @Query("select d from Deployment d where d.idempotencyKey = :idempotencyKey")
    Optional<Deployment> findByIdempotencyKey(UUID idempotencyKey);

    @Override
    @Query("select d from Deployment d where d.ownerKeycloakSub = :ownerKeycloakSub"
            + " and d.lifecycleState in ("
            + "   ch.alpenflight.deployments.domain.LifecycleState.TRIAL,"
            + "   ch.alpenflight.deployments.domain.LifecycleState.ACTIVE,"
            + "   ch.alpenflight.deployments.domain.LifecycleState.PAST_DUE,"
            + "   ch.alpenflight.deployments.domain.LifecycleState.CANCELLED)")
    Optional<Deployment> findActiveByOwner(UUID ownerKeycloakSub);
}
