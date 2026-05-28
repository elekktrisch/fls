package ch.alpenflight.deployments.infra;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data JPA implementation of the {@link DeploymentRepository}
 * domain port. The application layer depends on the abstract port
 * (ADR 0023) while Spring Data still generates the runtime bean.
 *
 * <p>{@link Deployment} carries no {@code @TenantId}; reads return rows
 * across every tenant. {@link #findByLifecycleStateIn} drives the
 * {@code @LifecycleStateFilter} aspect on every {@code @Scheduled} tick;
 * {@code ix_deployment_lifecycle} is the matching index per the
 * Performance plan.
 */
public interface JpaDeploymentRepository
        extends JpaRepository<Deployment, UUID>, DeploymentRepository {

    @Override
    @Query("select d from Deployment d where d.lifecycleState in :states order by d.id")
    List<Deployment> findByLifecycleStateIn(List<LifecycleState> states);

    @Override
    @Query("select d from Deployment d where d.idempotencyKey = :idempotencyKey")
    Optional<Deployment> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Inline-mirrors the {@code ux_deployment_owner_active} partial UNIQUE
     * predicate so the application read returns the same row the schema
     * would block. Keep the state-set in sync with the partial-index WHERE
     * clause (V14).
     */
    @Override
    @Query("select d from Deployment d where d.ownerKeycloakSub = :ownerKeycloakSub"
            + " and d.lifecycleState in ("
            + "   ch.alpenflight.deployments.domain.LifecycleState.TRIAL,"
            + "   ch.alpenflight.deployments.domain.LifecycleState.ACTIVE,"
            + "   ch.alpenflight.deployments.domain.LifecycleState.PAST_DUE,"
            + "   ch.alpenflight.deployments.domain.LifecycleState.CANCELLED)")
    Optional<Deployment> findActiveByOwner(UUID ownerKeycloakSub);
}
