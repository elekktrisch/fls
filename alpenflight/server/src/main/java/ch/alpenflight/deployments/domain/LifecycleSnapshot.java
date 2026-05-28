package ch.alpenflight.deployments.domain;

/**
 * Tight before/after payload for the {@code deployment.lifecycle_transition}
 * audit row. Carries only the lifecycle state; the security plan
 * excludes {@code name}, {@code ownerKeycloakSub}, billing IDs (operator
 * free-text + PCI scope adjacency), and the derived plan (computable
 * post-hoc from the state and never observed-in-row).
 *
 * <p>The audit redactor walks this record's fields; the policy is keyed
 * by entity-type {@code "Deployment"} — the snapshot type's class name
 * is orthogonal so an aggregate-shape change doesn't drift the policy
 * file.
 */
public record LifecycleSnapshot(LifecycleState lifecycleState) {
}
