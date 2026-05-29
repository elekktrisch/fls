/**
 * Cross-cutting scheduling primitives. Today: {@link UnscopedScheduledJob} —
 * the marker for {@code @Scheduled} jobs that operate on pre-tenant data
 * and intentionally bypass the per-(Deployment, Club) iteration that
 * {@code ch.alpenflight.deployments.application.LifecycleStateFilter}
 * performs.
 *
 * <p>Tenant-scoped jobs continue to pair {@code @Scheduled} with
 * {@code @LifecycleStateFilter}; only the unscoped escape hatch lives
 * here.
 */
@NullMarked
package ch.alpenflight.platform.scheduling;

import org.jspecify.annotations.NullMarked;
