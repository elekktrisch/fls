package ch.alpenflight.deployments.domain;

import org.jspecify.annotations.Nullable;

/**
 * Tight before/after payload for the {@code deployment.lifecycle_transition}
 * audit row. Carries only the lifecycle state + derived plan; the security
 * plan excludes {@code name}, {@code ownerKeycloakSub}, and billing IDs
 * from the audit JSON (operator free-text + PCI scope adjacency).
 *
 * <p>The audit redactor walks this record's fields; the policy is keyed by
 * entity-type {@code "Deployment"} — the snapshot type's class name is
 * orthogonal so an aggregate-shape change doesn't drift the policy file.
 *
 * @param lifecycleState the source / target state for the row's
 *                       {@code before_state} / {@code after_state}.
 *                       {@code null} {@code lifecycleState} would be
 *                       structurally invalid; callers pass {@code null}
 *                       for the snapshot itself when the transition has
 *                       no prior state (the {@code (none) → TRIAL} edge).
 * @param plan           the derived plan at this point in the transition.
 */
public record LifecycleSnapshot(LifecycleState lifecycleState, @Nullable Plan plan) {
}
