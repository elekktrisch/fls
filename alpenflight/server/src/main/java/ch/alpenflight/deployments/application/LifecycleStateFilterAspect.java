package ch.alpenflight.deployments.application;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spring AOP advice that wraps every {@code @Scheduled} method carrying a
 * {@link LifecycleStateFilter} annotation. The job body becomes a per
 * (Deployment, Club) callback — the advice queries Deployments matching
 * the filter, then for each Deployment iterates the child Clubs via
 * {@link DeploymentContext#forEachClub}, invoking the job body under
 * each Club's tenant scope.
 *
 * <p>An empty filter returns immediately without invoking the body —
 * fail-closed for the design-time invariant the ArchUnit rule enforces
 * at build time. Skipped Deployments emit a DEBUG line (NOT INFO — the
 * dunning-mode-noise discipline from the refinement edge-cases).
 *
 * <p>Today's joined-up flow: empty filter ↔ no work. Non-empty filter ↔
 * the body runs once per (Deployment, Club) pair under that Club's
 * tenant.
 */
@Aspect
@Component
public class LifecycleStateFilterAspect {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleStateFilterAspect.class);

    private final DeploymentContext deploymentContext;

    public LifecycleStateFilterAspect(DeploymentContext deploymentContext) {
        this.deploymentContext = deploymentContext;
    }

    @Around("@annotation(filter)")
    public @Nullable Object iterateEligibleDeployments(ProceedingJoinPoint pjp,
                                                       LifecycleStateFilter filter) throws Throwable {
        EnumSet<LifecycleState> eligible = filter.value().length == 0
                ? EnumSet.noneOf(LifecycleState.class)
                : EnumSet.copyOf(Arrays.asList(filter.value()));

        if (eligible.isEmpty()) {
            LOG.debug("LifecycleStateFilter empty on {} — skipping", methodFqn(pjp));
            return null;
        }

        for (Deployment deployment : deploymentContext.findDeployment(
                eligible.toArray(LifecycleState[]::new))) {
            UUID deploymentId = deployment.getId();
            if (deploymentId == null) {
                LOG.debug("Deployment with null id surfaced from repository — skipping");
                continue;
            }
            deploymentContext.forEachClub(deploymentId, club ->
                    invokeJobBody(pjp));
        }
        return null;
    }

    private static void invokeJobBody(ProceedingJoinPoint pjp) {
        try {
            pjp.proceed();
        } catch (Throwable t) {
            // Job-body failure propagates; the @Scheduled exception handler
            // / Spring's ScheduledTaskRegistry log it. We don't swallow — a
            // failing job should surface, not silently skip the rest of the
            // tick.
            throw new JobBodyFailure(t);
        }
    }

    private static String methodFqn(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return sig.getDeclaringTypeName() + "#" + sig.getName();
    }

    /**
     * Re-throw wrapper for checked-exception escape from the
     * {@link DeploymentContext#forEachClub} consumer lambda. Spring's
     * {@code @Scheduled} runner catches and logs it like any other
     * job-body throw.
     */
    static final class JobBodyFailure extends RuntimeException {
        JobBodyFailure(Throwable cause) {
            super(cause);
        }
    }
}
