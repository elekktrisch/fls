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

@Aspect
@Component
public class LifecycleStateFilterAspect {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleStateFilterAspect.class);

    private final DeploymentContext deploymentContext;

    public LifecycleStateFilterAspect(DeploymentContext deploymentContext) {
        this.deploymentContext = deploymentContext;
    }

    @Around("@annotation(filter) "
            + "&& @annotation(org.springframework.scheduling.annotation.Scheduled)")
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
                    invokeJobBodyInCurrentClubTenantScope(pjp));
        }
        return null;
    }

    private static void invokeJobBodyInCurrentClubTenantScope(ProceedingJoinPoint pjp) {
        try {
            pjp.proceed();
        } catch (Throwable t) {
            throw new JobBodyFailure(
                    "job body failure in " + methodFqn(pjp), t);
        }
    }

    private static String methodFqn(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        return sig.getDeclaringTypeName() + "#" + sig.getName();
    }

    static final class JobBodyFailure extends RuntimeException {
        JobBodyFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
