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

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentContext.class);

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

    /**
     * Runs {@code accumulator} for every Club of every Deployment in one of the
     * given lifecycle states, each under its own tenant scope, folding the
     * per-club results together — the shape every cross-tenant "Run now" needs.
     *
     * <p>A club whose turn throws is logged and stepped over, so one club's bad
     * data never denies the rest their run. That isolation lives here rather than
     * in each job, which is also why {@code jobName} is passed: it is what makes
     * the skip legible in the log.
     *
     * @param jobName     the job's registry key, for the skip log
     * @param identity    the empty result (an all-zero run summary)
     * @param accumulator merges one club's result into the running total
     */
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
