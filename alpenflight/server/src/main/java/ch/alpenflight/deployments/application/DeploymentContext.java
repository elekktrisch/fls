package ch.alpenflight.deployments.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * The single seam allowed to enumerate Clubs across tenants by parent
 * Deployment. Internally pushes each Club's id through
 * {@link Tenants#runAs} so the body executes under that Club's tenant
 * scope — the {@code ClubTenantIdentifierResolver} reads from the
 * carrier first and Hibernate's {@code @TenantId} filter appends
 * automatically to every query.
 *
 * <p>{@link Deployment} carries no {@code @TenantId} so the parent-side
 * query reads across every tenant; the child-side iteration switches
 * tenant per Club, never widening the window for the body.
 *
 * <p>Drives:
 * <ul>
 *   <li>{@link LifecycleStateFilterAspect} on every {@code @Scheduled}
 *       tick — selects eligible Deployments via {@link #findDeployment}
 *       and iterates their Clubs via {@link #forEachClub}.</li>
 *   <li>S-142 cleanup-cascade worker (future story) that walks Clubs of
 *       a {@code DELETING} Deployment.</li>
 *   <li>S-143 freemium-caps evaluator (future story) that iterates
 *       Clubs to count per-Deployment usage.</li>
 * </ul>
 *
 * <p>Performance contract: projection-only on the Club table — never
 * eager-loads aggregates under a null tenant. Each iteration opens its
 * own scope; the iteration order is deterministic by Club id (Performance
 * plan: partial-failure resumption is trivial).
 */
@Service
public class DeploymentContext {

    private final DeploymentRepository deployments;
    private final ClubRepository clubs;

    public DeploymentContext(DeploymentRepository deployments, ClubRepository clubs) {
        this.deployments = deployments;
        this.clubs = clubs;
    }

    /**
     * Returns Deployments whose lifecycle state matches one of the given
     * values. Drives the {@code @LifecycleStateFilter} aspect — empty
     * argument returns empty list (fail-closed: an aspect with an empty
     * filter doesn't fire).
     */
    public List<Deployment> findDeployment(LifecycleState... states) {
        if (states.length == 0) {
            return List.of();
        }
        return deployments.findByLifecycleStateIn(List.of(states));
    }

    /**
     * Iterates every Club under {@code deploymentId} under that Club's
     * tenant scope. The body sees a per-Club tenant context — Hibernate's
     * {@code @TenantId} filter applies; no cross-Club leakage.
     *
     * <p>The Club aggregate is loaded inside the per-Club scope so the
     * load itself runs under correct tenancy. If the row is absent
     * (soft-deleted concurrent with the iteration), the callback is
     * skipped silently — partial-failure resumption stays trivial.
     */
    public void forEachClub(UUID deploymentId, Consumer<Club> body) {
        List<UUID> clubIds = clubs.findIdsByDeploymentId(deploymentId);
        for (UUID clubId : clubIds) {
            Tenants.runAs(clubId, () ->
                    clubs.findActiveById(clubId).ifPresent(body));
        }
    }
}
