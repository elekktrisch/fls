/**
 * Deployments module — the tenancy parent above {@code Club}. One legacy
 * FLS upload bundle = one Deployment containing the 1..N Clubs that were
 * in that legacy install. The trial countdown, the subscription IDs, the
 * freemium plan, and the lifecycle state all live here; Club stays the
 * {@link org.hibernate.annotations.TenantId} carrier so cross-Club
 * isolation is preserved (vision C34).
 *
 * <p>Declared as an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module so consuming modules (S-138's trial
 * provisioning, future scheduled jobs via {@code @LifecycleStateFilter},
 * S-141 ingest) may import {@code deployments.domain.*} aggregates +
 * domain events directly. Matches the {@code clubs} / {@code audit}
 * shared-kernel surface.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code deployments.domain} — {@link ch.alpenflight.deployments.domain.Deployment}
 *       aggregate + {@link ch.alpenflight.deployments.domain.LifecycleState}
 *       enum + state-machine methods (per ADR 0022 directive 2) +
 *       {@link ch.alpenflight.deployments.domain.DeploymentLifecycleTransitioned}
 *       domain event + repository port + domain exceptions.</li>
 *   <li>{@code deployments.application} — admin service,
 *       {@link ch.alpenflight.deployments.application.DeploymentContext}
 *       for cross-Club iteration,
 *       {@link ch.alpenflight.deployments.application.LifecycleStateFilter}
 *       annotation + aspect, audit listener.</li>
 *   <li>{@code deployments.web} — admin REST controller + exception handler.</li>
 *   <li>{@code deployments.infra} — Spring Data JPA implementation.</li>
 * </ul>
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.deployments;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
