package ch.alpenflight.deployments.infra;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.util.List;
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
}
