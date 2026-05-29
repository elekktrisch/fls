package ch.alpenflight.platform.scheduling;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for a {@code @Scheduled} method that deliberately runs once per
 * tick, unscoped, across the whole database — bypassing the per-(Deployment,
 * Club) iteration that
 * {@link ch.alpenflight.deployments.application.LifecycleStateFilter}
 * performs.
 *
 * <p>Pre-tenant data (the signup-time {@code t_migration_upload} row
 * exists before any Deployment / Club; same for any future cluster-wide
 * housekeeping) has no Deployment context to filter against. Iterating
 * per Deployment would either run the job N times redundantly (cost) or
 * miss orphaned rows (correctness). The marker is the explicit "this job
 * is cross-Deployment by design" statement.
 *
 * <p>{@code ScheduledLifecycleFilterCoverageTest} accepts
 * {@link UnscopedScheduledJob} as an alternative to
 * {@link ch.alpenflight.deployments.application.LifecycleStateFilter} —
 * one of the two annotations must be present on every {@code @Scheduled}
 * method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UnscopedScheduledJob {
}
