package ch.alpenflight.deployments.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link Deployment} persistence. Implemented by
 * {@code ch.alpenflight.deployments.infra.JpaDeploymentRepository} which
 * extends both this interface and Spring Data's
 * {@code JpaRepository<Deployment, UUID>} so the application layer
 * depends on the abstract port (ADR 0023) while Spring Data still
 * generates the runtime implementation.
 *
 * <p>{@link Deployment} carries no {@code @TenantId} — it sits above the
 * tenancy boundary. Reads are unscoped; callers that iterate child Clubs
 * use {@code DeploymentContext.forEachClub} to switch the tenant per
 * Club inside an unscoped window.
 */
public interface DeploymentRepository {

    /** Returns the Deployment by id, or empty if absent. */
    Optional<Deployment> findById(UUID id);

    /** Returns Deployments whose lifecycle state matches one of the given values. */
    List<Deployment> findByLifecycleStateIn(List<LifecycleState> states);

    /** Persist (insert or update). Returns the managed entity. */
    Deployment save(Deployment deployment);
}
