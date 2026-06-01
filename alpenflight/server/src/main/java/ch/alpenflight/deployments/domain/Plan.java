package ch.alpenflight.deployments.domain;

/**
 * Freemium plan derived from {@link LifecycleState} on every transition.
 * Stored, not generated (ADR 0022 directive 2 forbids generated columns);
 * {@code Deployment.assertPlanConsistent()} re-asserts the derivation
 * invariant at the end of each transition method.
 *
 * <p>Derivation table:
 * <ul>
 *   <li>{@link LifecycleState#SANDBOX}, {@link LifecycleState#CANCELLED} → {@link #FREE}</li>
 *   <li>{@link LifecycleState#TRIAL}, {@link LifecycleState#ACTIVE},
 *       {@link LifecycleState#PAST_DUE} → {@link #ACTIVE} (past-due
 *       retains read access; gated writes blocked at S-143).</li>
 *   <li>{@link LifecycleState#DELETING} — plan preserved from prior state.
 *       Body never runs for {@code DELETING} Deployments (S-142 cascade
 *       owns the worker); plan reflects what was held at scheduleDelete.</li>
 * </ul>
 */
public enum Plan {
    FREE,
    ACTIVE
}
