package ch.alpenflight.deployments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeploymentRepository {

    Optional<Deployment> findById(UUID id);

    List<Deployment> findByLifecycleStateIn(List<LifecycleState> states);

    Optional<Deployment> findByIdempotencyKey(UUID idempotencyKey);

    Optional<Deployment> findActiveByOwner(UUID ownerKeycloakSub);

    Deployment save(Deployment deployment);
}
