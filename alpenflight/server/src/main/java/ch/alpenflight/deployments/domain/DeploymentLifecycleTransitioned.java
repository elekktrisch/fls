package ch.alpenflight.deployments.domain;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain event emitted by {@link Deployment} on every successful
 * lifecycle transition (including the synthetic null → {@code TRIAL} at
 * {@link Deployment#startTrial}). Picked up by Spring Data's
 * {@code @DomainEvents} publication on
 * {@link DeploymentRepository#save}; the application-layer audit listener
 * resolves actor + correlation-id from {@code SecurityContextHolder} /
 * MDC at listener-time and writes the {@code mutation_audit_event} row.
 *
 * <p>{@code fromState} is intentionally {@code @Nullable}: the
 * {@code (none) → TRIAL} initial transition stores a literal {@code null}
 * in the audit payload, NOT the string {@code "none"} — pinned for
 * downstream consumers.
 *
 * <p>Actor + tenant-context are deliberately NOT carried in this event —
 * they belong to the audit-write context, not the domain semantic. The
 * listener pulls them from the request thread's {@code SecurityContext}.
 */
public record DeploymentLifecycleTransitioned(UUID deploymentId,
                                               @Nullable LifecycleState fromState,
                                               LifecycleState toState,
                                               Instant occurredAt) {
}
