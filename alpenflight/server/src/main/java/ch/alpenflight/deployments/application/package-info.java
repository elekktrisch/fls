/**
 * Deployments application — the use-case layer.
 *
 * <ul>
 *   <li>{@link ch.alpenflight.deployments.application.DeploymentContext}
 *       — enumerates Deployments by lifecycle state + iterates child
 *       Clubs for cross-cutting reads (scheduled jobs, freemium-caps
 *       evaluator, S-142 cleanup-cascade). The single seam allowed to
 *       open a per-Club tenant window via
 *       {@link ch.alpenflight.platform.tenancy.Tenants#runAs}.</li>
 *   <li>{@link ch.alpenflight.deployments.application.LifecycleStateFilter}
 *       — declarative annotation paired with {@code @Scheduled}; the
 *       {@link ch.alpenflight.deployments.application.LifecycleStateFilterAspect}
 *       invokes the job body once per (Deployment, Club) under that
 *       Club's tenant scope and skips off-list states. Compile-time
 *       enforced by the
 *       {@code ScheduledLifecycleFilterCoverageTest} ArchUnit rule.</li>
 *   <li>{@link ch.alpenflight.deployments.application.LifecycleTransitionAuditListener}
 *       — subscribes to
 *       {@link ch.alpenflight.deployments.domain.DeploymentLifecycleTransitioned}
 *       and writes the {@code mutation_audit_event} row via the S-027
 *       {@link ch.alpenflight.audit.domain.AuditTrail} port.</li>
 *   <li>{@link ch.alpenflight.deployments.application.DeploymentsAdminService}
 *       — backs the {@code POST /api/v1/admin/deployments/{id}/lifecycle}
 *       endpoint.</li>
 * </ul>
 */
@NullMarked
package ch.alpenflight.deployments.application;

import org.jspecify.annotations.NullMarked;
