package ch.alpenflight.deployments.domain;

/**
 * Lifecycle of a {@link Deployment}. Stored as {@code @Enumerated(STRING)}
 * in {@code deployment.lifecycle_state}; the legal-transition table lives
 * on the aggregate per ADR 0022 directive 2.
 *
 * <ul>
 *   <li>{@link #SANDBOX} — the singleton shared-fixed Deployment that hosts
 *       the demo-area Clubs. Immutable: any transition out is illegal.
 *       Only {@code SandboxResetJob} (S-082) opts into this state via
 *       {@code @LifecycleStateFilter}.</li>
 *   <li>{@link #TRIAL} — first 72 h after a self-service ingest (S-138).
 *       Trial expiry flips to {@link #DELETING} (S-142).</li>
 *   <li>{@link #ACTIVE} — paid subscription (S-145) OR operator-flipped
 *       Deployment.</li>
 *   <li>{@link #PAST_DUE} — last payment failed; read access retained,
 *       gated writes blocked (S-143). Dunning grace then flips to
 *       {@link #CANCELLED}.</li>
 *   <li>{@link #CANCELLED} — subscription cancelled. Read access retained
 *       until the deletion grace fires.</li>
 *   <li>{@link #DELETING} — terminal-then-hard-deleted (no {@code deleted}
 *       state). S-142's cleanup worker cascades through Clubs +
 *       tenant-scoped rows; the Deployment row vanishes. Recovery to
 *       {@link #CANCELLED} is allowed within the grace window.</li>
 * </ul>
 */
public enum LifecycleState {
    SANDBOX,
    TRIAL,
    ACTIVE,
    PAST_DUE,
    CANCELLED,
    DELETING
}
