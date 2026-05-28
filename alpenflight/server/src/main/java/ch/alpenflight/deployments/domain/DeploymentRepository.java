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

    /**
     * Returns the Deployment bound to the given idempotency key, or empty
     * when none exists. Retried ingest of the same upload uses this
     * lookup to short-circuit to the existing Deployment instead of
     * provisioning a new one.
     */
    Optional<Deployment> findByIdempotencyKey(UUID idempotencyKey);

    /**
     * Returns the active (non-terminal) Deployment for the given owner, or
     * empty when the owner holds none. "Active" matches the structural
     * partial UNIQUE {@code ux_deployment_owner_active} — TRIAL / ACTIVE
     * / PAST_DUE / CANCELLED in, SANDBOX + DELETING out.
     *
     * <p>Drives the second-ingest 409 path: when the structural UNIQUE
     * violation surfaces on insert, the service re-reads through this
     * method to populate the structured 409 body.
     */
    Optional<Deployment> findActiveByOwner(UUID ownerKeycloakSub);

    /** Persist (insert or update). Returns the managed entity. */
    Deployment save(Deployment deployment);
}
