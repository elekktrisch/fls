package ch.alpenflight.deployments.application;

import ch.alpenflight.deployments.domain.LifecycleState;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative filter pairing with {@link org.springframework.scheduling.annotation.Scheduled}
 * on a scheduled-job method. The
 * {@link LifecycleStateFilterAspect} consults the {@link #value()} state
 * set on every tick: only Deployments whose
 * {@link ch.alpenflight.deployments.domain.Deployment#getLifecycleState}
 * is in the set receive the job-body invocation, and for each eligible
 * Deployment the body fires once per child Club under that Club's
 * tenant scope.
 *
 * <p>Operator-grilled 2026-05-28: ArchUnit-enforced — every
 * {@code @Scheduled} class MUST carry {@code @LifecycleStateFilter} with a
 * non-empty {@link #value()}. Missing annotation OR empty set = build
 * break. Defends against new jobs forgetting + leaking into
 * {@link LifecycleState#SANDBOX} (stranger data) or
 * {@link LifecycleState#DELETING} (cascade-in-flight).
 *
 * <p>Cross-cutting ops jobs that genuinely span every state declare each
 * state explicitly — the verbosity is the point.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LifecycleStateFilter {

    /**
     * The states in which the job body runs. Empty = build break (the
     * ArchUnit rule rejects). Varargs over Set for ergonomics; the aspect
     * normalises to an {@code EnumSet}.
     */
    LifecycleState[] value();
}
